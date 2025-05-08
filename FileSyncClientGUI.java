import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.attribute.BasicFileAttributes;

public class FileSyncClientGUI extends JFrame {
    private String serverHost = "localhost";
    private int serverPort = 8000;
    private String watchDir = "client_files";
    private final Map<String, Long> fileModificationTimes = new HashMap<>();

    private Socket socket;
    private ObjectOutputStream output;
    private ObjectInputStream input;
    private boolean connected = false;
    private Thread watchThread;
    private ScheduledExecutorService pollingExecutor; // Added for managing polling

    private JTextField serverHostField;
    private JTextField serverPortField;
    private JTextField watchDirField;
    private JButton connectButton;
    private JButton browseButton;
    private JTextArea logArea;
    private JTable fileTable;
    private DefaultTableModel fileTableModel;
    private JLabel statusLabel;

    private final List<String> activityLog = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public FileSyncClientGUI() {
        setTitle("File Sync Client");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        createGUI();
        initializeFileMap();
        setVisible(true);
    }

    private void createGUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JPanel connectionPanel = createConnectionPanel();
        mainPanel.add(connectionPanel, BorderLayout.NORTH);
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        
        JPanel filePanel = createFilePanel();
        splitPane.setTopComponent(filePanel);
        JPanel logPanel = createLogPanel();
        splitPane.setBottomComponent(logPanel);
        
        mainPanel.add(splitPane, BorderLayout.CENTER);
        
        statusLabel = new JLabel("Not connected");
        statusLabel.setBorder(new CompoundBorder(
                new EtchedBorder(), new EmptyBorder(5, 5, 5, 5)));
        mainPanel.add(statusLabel, BorderLayout.SOUTH);
        
        setContentPane(mainPanel);
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connection Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        
        panel.add(new JLabel("Server Host:"), gbc);
        gbc.gridx++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        serverHostField = new JTextField(serverHost, 15);
        panel.add(serverHostField, gbc);
        
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Server Port:"), gbc);
        gbc.gridx++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        serverPortField = new JTextField(String.valueOf(serverPort), 5);
        panel.add(serverPortField, gbc);
        
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Watch Directory:"), gbc);
        gbc.gridx++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        watchDirField = new JTextField(watchDir, 20);
        panel.add(watchDirField, gbc);
        
        // Browse button
        gbc.gridx++;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        browseButton = new JButton("Browse...");
        panel.add(browseButton, gbc);
        
        // Connect button
        gbc.gridx++;
        connectButton = new JButton("Connect");
        panel.add(connectButton, gbc);
        
        browseButton.addActionListener(e -> browseForDirectory());
        connectButton.addActionListener(e -> toggleConnection());
        
        return panel;
    }

    private JPanel createFilePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Synchronized Files"));
        
        fileTableModel = new DefaultTableModel(
                new Object[]{"File Name", "Path", "Size", "Last Modified", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        fileTable = new JTable(fileTableModel);
        fileTable.setFillsViewportHeight(true);
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(150); // File name
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(250); // Path
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(80);  // Size
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(150); // Last Modified
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(100); // Status
        JScrollPane scrollPane = new JScrollPane(fileTable);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        
        JButton refreshButton = new JButton("Refresh File List");
        refreshButton.addActionListener(e -> refreshFileList());
        toolBar.add(refreshButton);
        
        toolBar.addSeparator(); // Optional: adds a visual separator

        JButton addFileButton = new JButton("Add File...");
        addFileButton.addActionListener(e -> handleAddFileButton());
        toolBar.add(addFileButton);
        
        panel.add(toolBar, BorderLayout.NORTH);
        
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Activity Log"));
        
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        JButton clearButton = new JButton("Clear Log");
        clearButton.addActionListener(e -> clearLog());
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(clearButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }

    private void browseForDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Watch Directory");
        
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedDir = fileChooser.getSelectedFile();
            watchDirField.setText(selectedDir.getAbsolutePath());
        }
    }

    private void handleAddFileButton() {
        if (!connected) {
            JOptionPane.showMessageDialog(this, "Please connect to the server first.", "Not Connected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setDialogTitle("Select File to Add");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            Path sourcePath = selectedFile.toPath();
            Path destinationDir = Paths.get(watchDir);
            Path destinationPath = destinationDir.resolve(selectedFile.getName());

            try {
                // Ensure watchDir exists (it should if connected, but good practice)
                if (!Files.exists(destinationDir)) {
                    Files.createDirectories(destinationDir);
                    addLogEntry("Created watch directory: " + destinationDir);
                }

                Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
                addLogEntry("Copied file to watch directory: " + selectedFile.getName());
                // The WatchService should pick up this new file automatically.
                // refreshFileList(); // Optionally, refresh immediately, though WatchService should trigger it.
            } catch (IOException e) {
                addLogEntry("Error copying file '" + selectedFile.getName() + "' to watch directory: " + e.getMessage());
                JOptionPane.showMessageDialog(this,
                        "Could not copy file: " + e.getMessage(),
                        "File Copy Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void toggleConnection() {
        if (connected) {
            disconnectFromServer();
        
            connected = false;
            connectButton.setText("Connect");
            serverHostField.setEnabled(true);
            serverPortField.setEnabled(true);
            watchDirField.setEnabled(true);
            browseButton.setEnabled(true);
            statusLabel.setText("Not connected");
            return;
        }
    
        serverHost = serverHostField.getText().trim();
    
        try {
            serverPort = Integer.parseInt(serverPortField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, 
                    "Invalid port number. Please enter a valid integer.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        watchDir = watchDirField.getText().trim();
        File watchDirFile = new File(watchDir);
        if (!watchDirFile.exists() || !watchDirFile.isDirectory()) {
            int option = JOptionPane.showConfirmDialog(this,
                    "Watch directory does not exist. Create it?",
                    "Directory Not Found", JOptionPane.YES_NO_OPTION);
        
            if (option != JOptionPane.YES_OPTION) {
                return;
            }
        
            if (!watchDirFile.mkdirs()) {
                JOptionPane.showMessageDialog(this,
                        "Failed to create directory.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
    
        if (!connectToServer()) {
            return;
        }
    
        connected = true;
        connectButton.setText("Disconnect");
        serverHostField.setEnabled(false);
        serverPortField.setEnabled(false);
        watchDirField.setEnabled(false);
        browseButton.setEnabled(false);
        statusLabel.setText("Connected to " + serverHost + ":" + serverPort);
    
        startWatching();
    }

    private boolean connectToServer() {
        try {
            socket = new Socket(serverHost, serverPort);
            output = new ObjectOutputStream(socket.getOutputStream());
            input = new ObjectInputStream(socket.getInputStream());
            addLogEntry("Connected to server: " + serverHost + ":" + serverPort);
            return true;
        } catch (IOException e) {
            addLogEntry("Error connecting to server: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Could not connect to server: " + e.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void startWatching() {
        // Placeholder for file watching logic
        // This will be replaced by the actual WatchService implementation
        addLogEntry("Started watching directory: " + watchDir);
        // For now, just refresh the file list
        refreshFileList();

        // Example of how you might start a watch thread (actual implementation needed)
        watchThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Path path = Paths.get(watchDir);
                registerAll(path, watchService); // Register the directory and subdirectories

                // Initialize fileModificationTimes for existing files
                initializeFileMap();
                SwingUtilities.invokeLater(this::refreshFileList);

                // Start a separate service for polling modifications
                if (pollingExecutor == null || pollingExecutor.isShutdown()) {
                    pollingExecutor = Executors.newSingleThreadScheduledExecutor();
                }
                pollingExecutor.scheduleAtFixedRate(this::pollForModificationsRunnable, 0, 1, TimeUnit.SECONDS);

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        key = watchService.take(); // Wait for an event
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // Preserve interrupt status
                        break;
                    }

                    Path dir = (Path) key.watchable();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        Path name = (Path) event.context();
                        Path child = dir.resolve(name);
                        // Normalize relativePath to use '/' separators
                        String relativePath = Paths.get(watchDir).relativize(child).toString().replace(File.separatorChar, '/');

                        if (kind == OVERFLOW) {
                            addLogEntry("Warning: WatchService event overflow occurred.");
                            continue;
                        }
                        
                        if (kind == ENTRY_CREATE) {
                            if (Files.isDirectory(child)) {
                                registerAll(child, watchService); // Register new subdirectory
                                addLogEntry("New directory registered: " + relativePath);
                            } else {
                                handleCreateEvent(child, relativePath);
                            }
                        } else if (kind == ENTRY_MODIFY) {
                            if (!Files.isDirectory(child)) { // Only handle modify for files
                                handleModifyEvent(child, relativePath);
                            }
                        } else if (kind == ENTRY_DELETE) {
                             // No need to check if it's a directory, just send delete event
                            handleDeleteEvent(relativePath);
                        }
                    }
                    boolean valid = key.reset();
                    if (!valid) {
                        addLogEntry("WatchKey no longer valid for: " + dir);
                        // Re-register or handle error
                        break; 
                    }
                }
            } catch (IOException e) {
                addLogEntry("Error in watch service: " + e.getMessage());
            } catch (ClosedWatchServiceException e) {
                addLogEntry("Watch service closed.");
            } finally {
                addLogEntry("Stopped watching directory: " + watchDir);
            }
        });
        watchThread.setDaemon(true);
        watchThread.start();
    }
    
    private void initializeFileMap() {
        fileModificationTimes.clear();
        File dir = new File(watchDir);
        if (dir.exists() && dir.isDirectory()) {
            scanDirectoryForFiles(dir, "");
        }
        refreshFileList(); // Refresh UI after initializing
    }

    private void scanDirectoryForFiles(File directory, String parentPath) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                String currentSegment = file.getName();
                // Ensure relativePath uses '/' separators
                String relativePath = parentPath.isEmpty() ? currentSegment : parentPath + "/" + currentSegment;
                if (file.isDirectory()) {
                    scanDirectoryForFiles(file, relativePath); // Recursively scan subdirectories
                } else {
                    fileModificationTimes.put(relativePath, file.lastModified());
                }
            }
        }
    }


    private void pollForModificationsRunnable() { // Renamed and modified for ScheduledExecutorService
        // This method is now run by the ScheduledExecutorService
        // No need for an outer loop or Thread.sleep(1000) here
        // No need to catch InterruptedException here as the executor handles it
        
        // Create a copy of the keys to avoid ConcurrentModificationException if an event modifies the map
        // though with single-threaded polling and SwingUtilities.invokeLater for modifications, it might be less of an issue.
        Set<String> filePathsSnapshot = new HashSet<>(fileModificationTimes.keySet());

        for (String filePathKey : filePathsSnapshot) { // filePathKey is normalized with '/'
            Long lastModifiedTime = fileModificationTimes.get(filePathKey);
            if (lastModifiedTime == null) continue; // Should not happen if snapshot is from keys

            // Construct file path using system-dependent separator for File object
            File file = new File(watchDir, filePathKey.replace('/', File.separatorChar));
            
            if (file.exists() && file.isFile()) {
                long currentModifiedTime = file.lastModified();
                
                if (currentModifiedTime > lastModifiedTime) {
                    // Use the normalized filePathKey for the event
                    final String finalFilePathKey = filePathKey;
                    SwingUtilities.invokeLater(() -> handleModifyEvent(file.toPath(), finalFilePathKey));
                }
            } else {
                // File might have been deleted, WatchService should handle this with ENTRY_DELETE
                // Or, if it's a directory, this polling logic isn't meant for it.
            }
        }
    }

    private void handleCreateEvent(Path fullPath, String relativePath) {
        try {
            byte[] fileData = null;
            int retries = 3;
            long delayMs = 50; // Start with a short delay

            for (int i = 0; i < retries; i++) {
                try {
                    fileData = Files.readAllBytes(fullPath);
                    break; // Success
                } catch (IOException e) {
                    addLogEntry("Attempt " + (i + 1) + " to read file " + relativePath + " (create event) failed: " + e.getMessage());
                    if (i < retries - 1) {
                        try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                        delayMs *= 2; // Optional: increase delay
                    } else {
                        addLogEntry("Error reading file during create event after " + retries + " attempts: " + relativePath + " - " + e.getMessage());
                        return; 
                    }
                }
            }

            if (fileData == null) {
                 addLogEntry("Could not read file data for create event after retries: " + relativePath);
                 return;
            }
            
            addLogEntry("File created: " + relativePath);
            // byte[] fileData = Files.readAllBytes(fullPath); // Original line removed
            FileEvent event = new FileEvent(FileEvent.EventType.CREATE, relativePath, fileData);
            sendEventToServer(event);
            fileModificationTimes.put(relativePath, fullPath.toFile().lastModified());
            SwingUtilities.invokeLater(this::refreshFileList);
        } catch (Exception e) { // Catch any other unexpected errors from logic outside retry
            addLogEntry("Unexpected error handling create event for file: " + relativePath + " - " + e.getMessage());
        }
    }
    
    private void handleModifyEvent(Path fullPath, String relativePath) {
        try {
            byte[] fileData = null;
            int retries = 3;
            long delayMs = 50; // Start with a short delay

            for (int i = 0; i < retries; i++) {
                try {
                    fileData = Files.readAllBytes(fullPath);
                    break; // Success
                } catch (IOException e) {
                    addLogEntry("Attempt " + (i + 1) + " to read file " + relativePath + " (modify event) failed: " + e.getMessage());
                    if (i < retries - 1) {
                        try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                        delayMs *= 2; // Optional: increase delay
                    } else {
                        addLogEntry("Error reading file during modify event after " + retries + " attempts: " + relativePath + " - " + e.getMessage());
                        return;
                    }
                }
            }

            if (fileData == null) {
                 addLogEntry("Could not read file data for modify event after retries: " + relativePath);
                 return;
            }

            long currentModifiedTime = fullPath.toFile().lastModified(); // Get time before sending
            addLogEntry("File modified: " + relativePath);
            // byte[] fileData = Files.readAllBytes(fullPath); // Original line removed
            FileEvent event = new FileEvent(FileEvent.EventType.MODIFY, relativePath, fileData);
            sendEventToServer(event);
            // Update modification time after successful send
            fileModificationTimes.put(relativePath, currentModifiedTime);
            SwingUtilities.invokeLater(this::refreshFileList);
        } catch (Exception e) { // Catch any other unexpected errors
            addLogEntry("Unexpected error handling modify event for file: " + relativePath + " - " + e.getMessage());
        }
    }
    
    private void handleDeleteEvent(String relativePath) {
        try {
            addLogEntry("File deleted: " + relativePath);
            FileEvent event = new FileEvent(FileEvent.EventType.DELETE, relativePath, null);
            sendEventToServer(event);
            fileModificationTimes.remove(relativePath);
            SwingUtilities.invokeLater(this::refreshFileList);
        } catch (Exception e) { // Catch any other unexpected errors from sendEventToServer or map removal
            addLogEntry("Unexpected error handling delete event for file: " + relativePath + " - " + e.getMessage());
        }
    }

    private synchronized void sendEventToServer(FileEvent event) {
        try {
            output.writeObject(event);
            output.flush();
            String ack = (String) input.readObject();
            addLogEntry("Server response: " + ack);
        } catch (IOException e) {
            addLogEntry("IOException sending event to server: " + e.getMessage());
            SwingUtilities.invokeLater(this::tryReconnect); // Attempt to reconnect on IO error
        } catch (ClassNotFoundException e) {
            addLogEntry("ClassNotFoundException receiving server response: " + e.getMessage());
            // This might indicate a protocol mismatch or corruption, might need specific handling
        } catch (Exception e) { // Catch any other unexpected runtime errors
            addLogEntry("Unexpected error sending event to server: " + e.getMessage());
            SwingUtilities.invokeLater(this::tryReconnect); // Attempt to reconnect on other errors too
        }
    }

    private void tryReconnect() {
        addLogEntry("Trying to reconnect to server...");
        
        disconnectFromServer();
        connected = false;
        connectButton.setText("Connect");
        serverHostField.setEnabled(true);
        serverPortField.setEnabled(true);
        watchDirField.setEnabled(true);
        browseButton.setEnabled(true);
        statusLabel.setText("Connection lost. Please reconnect.");
        
        int option = JOptionPane.showConfirmDialog(this,
                "Connection to server lost. Would you like to reconnect?",
                "Connection Lost", JOptionPane.YES_NO_OPTION);
        
        if (option == JOptionPane.YES_OPTION) {
            toggleConnection();
        }
    }

    private void addLogEntry(String message) {
        String timestamp = timeFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;
        
        activityLog.add(logEntry);
        SwingUtilities.invokeLater(() -> {
            logArea.append(logEntry + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void clearLog() {
        activityLog.clear();
        logArea.setText("");
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
            // Log this error or show a simple dialog, but don't use printStackTrace in a GUI app's final version
            System.err.println("Could not set system look and feel: " + e.getMessage());
            // Optionally, show a basic error dialog to the user if this is critical
            // JOptionPane.showMessageDialog(null, "Error setting up UI: " + e.getMessage(), "UI Error", JOptionPane.ERROR_MESSAGE);
        }
        
        SwingUtilities.invokeLater(() -> new FileSyncClientGUI());
    }

    private void disconnectFromServer() {
        addLogEntry("Disconnecting from server...");
        try {
            if (pollingExecutor != null && !pollingExecutor.isShutdown()) {
                pollingExecutor.shutdownNow(); // Attempt to stop all actively executing tasks
                try {
                    // Wait a while for existing tasks to terminate
                    if (!pollingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        addLogEntry("Polling executor did not terminate in time.");
                    }
                } catch (InterruptedException ie) {
                    // (Re-)Cancel if current thread also interrupted
                    pollingExecutor.shutdownNow();
                    // Preserve interrupt status
                    Thread.currentThread().interrupt();
                }
            }
            if (watchThread != null && watchThread.isAlive()) {
                watchThread.interrupt(); // Interrupt the watch thread
                try {
                    watchThread.join(5000); // Wait for the watch thread to die
                    if (watchThread.isAlive()) {
                        addLogEntry("Watch thread did not terminate in time.");
                    }
                } catch (InterruptedException e) {
                    addLogEntry("Interrupted while waiting for watch thread to terminate.");
                    Thread.currentThread().interrupt(); // Preserve interrupt status
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    addLogEntry("Error closing output stream: " + e.getMessage());
                }
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    addLogEntry("Error closing input stream: " + e.getMessage());
                }
            }
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    addLogEntry("Error closing socket: " + e.getMessage());
                }
            }
        } finally {
            // Nullify resources to indicate they are closed and to allow GC
            socket = null;
            output = null;
            input = null;
            // watchThread = null; // Not strictly necessary to nullify thread objects
            // pollingExecutor = null; // Not strictly necessary
            connected = false; // Ensure connection status is updated

            // Update UI components on the EDT
            SwingUtilities.invokeLater(() -> {
                connectButton.setText("Connect");
                serverHostField.setEnabled(true);
                serverPortField.setEnabled(true);
                watchDirField.setEnabled(true);
                browseButton.setEnabled(true);
                if (statusLabel != null) { // Check if statusLabel is initialized
                    statusLabel.setText("Disconnected");
                }
            });
            addLogEntry("Successfully disconnected from server.");
        }
    }

    private void registerAll(final Path start, final WatchService watcher) throws IOException {
        // Register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void refreshFileList() {
        fileTableModel.setRowCount(0); // Clear existing rows
        Path rootDir = Paths.get(watchDir);

        if (!Files.exists(rootDir) || !Files.isDirectory(rootDir)) {
            addLogEntry("Watch directory does not exist or is not a directory: " + watchDir);
            return;
        }

        try {
            Files.walkFileTree(rootDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        String fileName = file.getFileName().toString();
                        String absolutePath = file.toAbsolutePath().toString();
                        long fileSize = attrs.size();
                        String lastModified = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                .format(new Date(attrs.lastModifiedTime().toMillis()));
                        
                        String relativePath = rootDir.relativize(file).toString().replace(File.separatorChar, '/'); // Ensure consistent path separators
                        
                        String status = fileModificationTimes.containsKey(relativePath) ? "Tracked" : "Untracked";
                        
                        SwingUtilities.invokeLater(() -> {
                            fileTableModel.addRow(new Object[]{fileName, absolutePath, fileSize, lastModified, status});
                        });
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    // Log error but continue walking
                    addLogEntry("Error accessing file: " + file.toString() + " - " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        addLogEntry("Error accessing directory: " + dir.toString() + " - " + exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            addLogEntry("Error refreshing file list: " + e.getMessage());
        }
        addLogEntry("File list refreshed.");
    }
}