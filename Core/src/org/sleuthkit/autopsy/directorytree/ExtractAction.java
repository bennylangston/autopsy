/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-2018 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this content except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.directorytree;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import javax.swing.AbstractAction;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import org.netbeans.api.progress.ProgressHandle;
import org.openide.util.Cancellable;
import org.openide.util.NbBundle;
import org.openide.util.Utilities;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.coreutils.FileUtil;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.MessageNotifyUtil;
import org.sleuthkit.autopsy.datamodel.ContentUtils;
import org.sleuthkit.autopsy.datamodel.ContentUtils.ExtractFscContentVisitor;
import org.sleuthkit.datamodel.AbstractFile;

/**
 * Extracts AbstractFiles to a location selected by the user.
 */
public final class ExtractAction extends AbstractAction {

    private Logger logger = Logger.getLogger(ExtractAction.class.getName());

    private String userDefinedExportPath;

    // This class is a singleton to support multi-selection of nodes, since 
    // org.openide.nodes.NodeOp.findActions(Node[] nodes) will only pick up an Action if every 
    // node in the array returns a reference to the same action object from Node.getActions(boolean).    
    private static ExtractAction instance;

    public static synchronized ExtractAction getInstance() {
        if (null == instance) {
            instance = new ExtractAction();
        }
        return instance;
    }

    /**
     * Private constructor for the action.
     */
    private ExtractAction() {
        super(NbBundle.getMessage(ExtractAction.class, "ExtractAction.title.extractFiles.text"));
    }

    /**
     * Asks user to choose destination, then extracts content to destination
     * (recursing on directories).
     *
     * @param e The action event.
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        Collection<? extends AbstractFile> selectedFiles = Utilities.actionsGlobalContext().lookupAll(AbstractFile.class);
        if (selectedFiles.size() > 1) {
            extractFiles(e, selectedFiles);
        } else if (selectedFiles.size() == 1) {
            AbstractFile source = selectedFiles.iterator().next();
            if (source.isDir()) {
                extractFiles(e, selectedFiles);
            } else {
                extractFile(e, selectedFiles.iterator().next());
            }
        }
    }

    /**
     * Called when user has selected a single file to extract
     *
     * @param event
     * @param selectedFile Selected file
     */
    @NbBundle.Messages({"ExtractAction.noOpenCase.errMsg=No open case available."})
    private void extractFile(ActionEvent event, AbstractFile selectedFile) {
        Case openCase;
        try {
            openCase = Case.getCurrentCaseThrows();
        } catch (NoCurrentCaseException ex) {
            JOptionPane.showMessageDialog((Component) event.getSource(), Bundle.ExtractAction_noOpenCase_errMsg());
            logger.log(Level.INFO, "Exception while getting open case.", ex); //NON-NLS
            return;
        }
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(getExportDirectory(openCase)));
        // If there is an attribute name, change the ":". Otherwise the extracted file will be hidden
        fileChooser.setSelectedFile(new File(FileUtil.escapeFileName(selectedFile.getName())));
        if (fileChooser.showSaveDialog((Component) event.getSource()) == JFileChooser.APPROVE_OPTION) {
            updateExportDirectory(fileChooser.getSelectedFile().getParent(), openCase);
            
            ArrayList<FileExtractionTask> fileExtractionTasks = new ArrayList<>();
            fileExtractionTasks.add(new FileExtractionTask(selectedFile, fileChooser.getSelectedFile()));
            runExtractionTasks(event, fileExtractionTasks);
        }
    }

    /**
     * Called when a user has selected multiple files to extract
     *
     * @param event
     * @param selectedFiles Selected files
     */
    private void extractFiles(ActionEvent event, Collection<? extends AbstractFile> selectedFiles) {
        Case openCase;
        try {
            openCase = Case.getCurrentCaseThrows();
        } catch (NoCurrentCaseException ex) {
            JOptionPane.showMessageDialog((Component) event.getSource(), Bundle.ExtractAction_noOpenCase_errMsg());
            logger.log(Level.INFO, "Exception while getting open case.", ex); //NON-NLS
            return;
        }
        JFileChooser folderChooser = new JFileChooser();
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        folderChooser.setCurrentDirectory(new File(getExportDirectory(openCase)));
        if (folderChooser.showSaveDialog((Component) event.getSource()) == JFileChooser.APPROVE_OPTION) {
            File destinationFolder = folderChooser.getSelectedFile();
            if (!destinationFolder.exists()) {
                try {
                    destinationFolder.mkdirs();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog((Component) event.getSource(), NbBundle.getMessage(this.getClass(),
                            "ExtractAction.extractFiles.cantCreateFolderErr.msg"));
                    logger.log(Level.INFO, "Unable to create folder(s) for user " + destinationFolder.getAbsolutePath(), ex); //NON-NLS
                    return;
                }
            }
            updateExportDirectory(destinationFolder.getPath(), openCase);

            /*
             * get the unique set of files from the list. A user once reported
             * extraction taking days because it was extracting the same PST
             * file 20k times. They selected 20k email messages in the tree and
             * chose to extract them.
             */
            Set<AbstractFile> uniqueFiles = new HashSet<>(selectedFiles);

            // make a task for each file
            ArrayList<FileExtractionTask> fileExtractionTasks = new ArrayList<>();
            for (AbstractFile source : uniqueFiles) {
                // If there is an attribute name, change the ":". Otherwise the extracted file will be hidden
                fileExtractionTasks.add(new FileExtractionTask(source, new File(destinationFolder, source.getId() + "-" + FileUtil.escapeFileName(source.getName()))));
            }
            runExtractionTasks(event, fileExtractionTasks);
        }
    }

    /**
     * Get the export directory path.
     *
     * @param openCase The current case.
     *
     * @return The export directory path.
     */
    private String getExportDirectory(Case openCase) {
        String caseExportPath = openCase.getExportDirectory();

        if (userDefinedExportPath == null) {
            return caseExportPath;
        }

        File file = new File(userDefinedExportPath);
        if (file.exists() == false || file.isDirectory() == false) {
            return caseExportPath;
        }

        return userDefinedExportPath;
    }

    /**
     * Update the default export directory. If the directory path matches the
     * case export directory, then the directory used will always match the
     * export directory of any given case. Otherwise, the path last used will be
     * saved.
     *
     * @param exportPath The export path.
     * @param openCase   The current case.
     */
    private void updateExportDirectory(String exportPath, Case openCase) {
        if (exportPath.equalsIgnoreCase(openCase.getExportDirectory())) {
            userDefinedExportPath = null;
        } else {
            userDefinedExportPath = exportPath;
        }
    }

    /**
     * Execute a series of file extraction tasks.
     *
     * @param event               ActionEvent whose source will be used for
     *                            centering popup dialogs.
     * @param fileExtractionTasks List of file extraction tasks.
     */
    private void runExtractionTasks(ActionEvent event, List<FileExtractionTask> fileExtractionTasks) {

        // verify all of the sources and destinations are OK
        for (Iterator<FileExtractionTask> it = fileExtractionTasks.iterator(); it.hasNext();) {
            FileExtractionTask task = it.next();

            if (ContentUtils.isDotDirectory(task.source)) {
                //JOptionPane.showMessageDialog((Component) e.getSource(), "Cannot extract virtual " + task.source.getName() + " directory.", "File is Virtual Directory", JOptionPane.WARNING_MESSAGE);
                it.remove();
                continue;
            }

            /*
             * This code assumes that each destination is unique. We previously
             * satisfied that by adding the unique ID.
             */
            if (task.destination.exists()) {
                if (JOptionPane.showConfirmDialog((Component) event.getSource(),
                        NbBundle.getMessage(this.getClass(), "ExtractAction.confDlg.destFileExist.msg", task.destination.getAbsolutePath()),
                        NbBundle.getMessage(this.getClass(), "ExtractAction.confDlg.destFileExist.title"),
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    if (!FileUtil.deleteFileDir(task.destination)) {
                        JOptionPane.showMessageDialog((Component) event.getSource(),
                                NbBundle.getMessage(this.getClass(), "ExtractAction.msgDlg.cantOverwriteFile.msg", task.destination.getAbsolutePath()));
                        it.remove();
                    }
                } else {
                    it.remove();
                }
            }
        }

        // launch a thread to do the work
        if (!fileExtractionTasks.isEmpty()) {
            try {
                FileExtracter extracter = new FileExtracter(fileExtractionTasks);
                extracter.execute();
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Unable to start background file extraction thread", ex); //NON-NLS
            }
        } else {
            MessageNotifyUtil.Message.info(
                    NbBundle.getMessage(this.getClass(), "ExtractAction.notifyDlg.noFileToExtr.msg"));
        }
    }

    /**
     * Stores source and destination for file extraction.
     */
    private class FileExtractionTask {

        AbstractFile source;
        File destination;

        /**
         * Create an instance of the FileExtractionTask.
         *
         * @param source      The file to be extracted.
         * @param destination The destination for the extraction.
         */
        FileExtractionTask(AbstractFile source, File destination) {
            this.source = source;
            this.destination = destination;
        }
    }

    /**
     * Thread that does the actual extraction work
     */
    private class FileExtracter extends SwingWorker<Object, Void> {

        private final Logger logger = Logger.getLogger(FileExtracter.class.getName());
        private ProgressHandle progress;
        private final List<FileExtractionTask> extractionTasks;

        /**
         * Create an instance of the FileExtracter.
         *
         * @param extractionTasks List of file extraction tasks.
         */
        FileExtracter(List<FileExtractionTask> extractionTasks) {
            this.extractionTasks = extractionTasks;
        }

        @Override
        protected Object doInBackground() throws Exception {
            if (extractionTasks.isEmpty()) {
                return null;
            }

            // Setup progress bar.
            final String displayName = NbBundle.getMessage(this.getClass(), "ExtractAction.progress.extracting");
            progress = ProgressHandle.createHandle(displayName, new Cancellable() {
                @Override
                public boolean cancel() {
                    if (progress != null) {
                        progress.setDisplayName(
                                NbBundle.getMessage(this.getClass(), "ExtractAction.progress.cancellingExtraction", displayName));
                    }
                    return ExtractAction.FileExtracter.this.cancel(true);
                }
            });
            progress.start();
            progress.switchToIndeterminate();

            /*
             * @@@ Add back in -> Causes exceptions int workUnits = 0; for
             * (FileExtractionTask task : extractionTasks) { workUnits +=
             * calculateProgressBarWorkUnits(task.source); }
             * progress.switchToDeterminate(workUnits);
             */
            // Do the extraction tasks.
            for (FileExtractionTask task : this.extractionTasks) {
                // @@@ Note, we are no longer passing in progress
                ExtractFscContentVisitor.extract(task.source, task.destination, null, this);
            }

            return null;
        }

        @Override
        protected void done() {
            boolean msgDisplayed = false;
            try {
                super.get();
            } catch (InterruptedException | ExecutionException ex) {
                logger.log(Level.SEVERE, "Fatal error during file extraction", ex); //NON-NLS
                MessageNotifyUtil.Message.info(
                        NbBundle.getMessage(this.getClass(), "ExtractAction.done.notifyMsg.extractErr", ex.getMessage()));
                msgDisplayed = true;
            } finally {
                progress.finish();
                if (!this.isCancelled() && !msgDisplayed) {
                    MessageNotifyUtil.Message.info(
                            NbBundle.getMessage(this.getClass(), "ExtractAction.done.notifyMsg.fileExtr.text"));
                }
            }
        }

        /**
         * Calculate the number of work units for the progress bar.
         *
         * @param file File whose children will be reviewed to get the number of
         *             work units.
         *
         * @return The number of work units.
         */
        /*
         * private int calculateProgressBarWorkUnits(AbstractFile file) { int
         * workUnits = 0; if (file.isFile()) { workUnits += file.getSize(); }
         * else { try { for (Content child : file.getChildren()) { if (child
         * instanceof AbstractFile) { workUnits +=
         * calculateProgressBarWorkUnits((AbstractFile) child); } } } catch
         * (TskCoreException ex) { logger.log(Level.SEVERE, "Could not get
         * children of content", ex); //NON-NLS } } return workUnits;
        }
         */
    }
}
