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
import java.util.UUID; // Added for client ID generation
import java.util.Set;   // Added for server manifest
import java.util.HashSet; // Added for server manifest

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
    private String clientId; // Unique ID for this client GUI instance
    private final Set<String> serverKnownFilesAfterHandshake = new HashSet<>(); // To store files known by server after handshake


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
        this.clientId = UUID.randomUUID().toString(); // Initialize unique client ID
        setTitle("File Sync Client (ID: " + clientId.substring(0, 8) + "...)");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        createGUI();
        initializeFileMap();
        setVisible(true);
        addLogEntry("Client GUI initialized. ID: " + clientId);
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
        if (!connected) {
            serverHost = serverHostField.getText().trim();
            watchDir = watchDirField.getText().trim();
            try {
                serverPort = Integer.parseInt(serverPortField.getText().trim());
            } catch (NumberFormatException e) {
                addLogEntry("Invalid port number: " + serverPortField.getText());
                statusLabel.setText("Error: Invalid port.");
                JOptionPane.showMessageDialog(this, "Invalid port number.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (serverHost.isEmpty() || watchDir.isEmpty()) {
                addLogEntry("Server host and watch directory cannot be empty.");
                JOptionPane.showMessageDialog(this, "Server host and watch directory must be specified.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            File dir = new File(watchDir);
            if (!dir.exists() || !dir.isDirectory()) {
                addLogEntry("Watch directory is not valid: " + watchDir);
                JOptionPane.showMessageDialog(this, "The specified watch directory is not valid or does not exist.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }


            if (connectToServer()) {
                connectButton.setText("Disconnect");
                serverHostField.setEnabled(false);
                serverPortField.setEnabled(false);
                watchDirField.setEnabled(false);
                browseButton.setEnabled(false);
                startWatching(); // Start watching after successful connection and handshake
            }
        } else {
            disconnectFromServer();
            connectButton.setText("Connect");
            serverHostField.setEnabled(true);
            serverPortField.setEnabled(true);
            watchDirField.setEnabled(true);
            browseButton.setEnabled(true);
        }
    }

    private boolean connectToServer() {
        try {
            // Ensure values are taken from fields at the moment of connection
            this.serverHost = serverHostField.getText().trim();
            this.watchDir = watchDirField.getText().trim();
            this.serverPort = Integer.parseInt(serverPortField.getText().trim());

            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Attempting to connect to " + serverHost + ":" + serverPort + "...");
            socket = new Socket();
            socket.connect(new InetSocketAddress(this.serverHost, this.serverPort), 5000); // 5-second connection timeout
            socket.setSoTimeout(15000); // 15-second read timeout for handshake operations

            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Socket connected.");

            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush(); // Crucial: send stream header immediately
            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: ObjectOutputStream created and flushed.");

            input = new ObjectInputStream(socket.getInputStream());
            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: ObjectInputStream created.");

            // Send client ID to the server
            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Sending client ID to server...");
            output.writeObject(this.clientId);
            output.flush(); // Ensure the client ID is sent immediately
            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Client ID sent. Waiting for server file manifest...");

            // After sending client ID, expect server to send its manifest (list of known files)
            Object serverResponse = input.readObject(); // This call is subject to the SO_TIMEOUT
            if (serverResponse instanceof Set) {
                @SuppressWarnings("unchecked") // We expect a Set of Strings from the server
                Set<String> receivedManifest = (Set<String>) serverResponse;
                
                synchronized (serverKnownFilesAfterHandshake) {
                    serverKnownFilesAfterHandshake.clear();
                    serverKnownFilesAfterHandshake.addAll(receivedManifest);
                }
                addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Received initial file manifest from server. " +
                               serverKnownFilesAfterHandshake.size() + " files known by server.");
            } else {
                String responseType = (serverResponse != null) ? serverResponse.getClass().getName() : "null";
                addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Error: Expected Set<String> manifest from server, got " + responseType);
                throw new IOException("Unexpected response from server during handshake (manifest was type " + responseType + ").");
            }

            connected = true;
            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Successfully connected and handshake complete with server " + this.serverHost + ":" + this.serverPort);
            statusLabel.setText("Connected to " + this.serverHost + ":" + this.serverPort);
            
            // Reset SO_TIMEOUT to 0 for indefinite blocking for normal operations, or keep a longer one if preferred
            // socket.setSoTimeout(0); 

            initializeFileMap(); // Re-scan local files
            refreshFileList();   // Update GUI table

            return true;
        } catch (NumberFormatException e) {
            addLogEntry("Client [GUI:" + clientId.substring(0,8) + "]: Error: Invalid port number format - " + serverPortField.getText());
            statusLabel.setText("Error: Invalid port.");
            return false; // Already handled in toggleConnection, but good to be safe
        } catch (IOException | ClassNotFoundException e) {
            String effectiveClientId = (this.clientId != null) ? this.clientId.substring(0,8) : "N/A";
            addLogEntry("Client [GUI:" + effectiveClientId + "] Error connecting/handshake: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            statusLabel.setText("Connection failed: " + e.getMessage());
            disconnectFromServer(); // Ensure resources are cleaned up
            return false;
        }
    }

    private void startWatching() {
        addLogEntry("Started watching directory: " + watchDir);
        refreshFileList();

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
        // This method will now just ensure the map is ready.
        // The actual scanning and table update is handled by refreshFileList.
        synchronized (fileModificationTimes) {
            fileModificationTimes.clear();
        }
        // Initial scan and table population will be done by the first call to refreshFileList
    }

    private void scanDirectoryForFiles(File directory, String parentPath) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            String relativePath = parentPath.isEmpty() ? file.getName() : parentPath + File.separator + file.getName();
            if (file.isDirectory()) {
                scanDirectoryForFiles(file, relativePath);
            } else {
                // Only update the map, do not touch fileTableModel here
                synchronized (fileModificationTimes) {
                    fileModificationTimes.put(relativePath.replace(File.separatorChar, '/'), file.lastModified());
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
        byte[] fileData = null;
        int maxRetries = 5; // Increased max retries
        long initialDelayMs = 100; // Initial delay
        long currentDelayMs = initialDelayMs;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                fileData = Files.readAllBytes(fullPath);
                addLogEntry("Successfully read file " + relativePath + " (create event) on attempt " + attempt);
                break; // Success
            } catch (IOException e) {
                addLogEntry("Attempt " + attempt + "/" + maxRetries + " to read file " + relativePath +
                            " (create event) failed: " + e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(currentDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        addLogEntry("File read retry for " + relativePath + " interrupted. Aborting create event.");
                        return;
                    }
                    currentDelayMs *= 2; // Exponential backoff
                } else {
                    addLogEntry("Failed to read file " + relativePath + " (create event) after " +
                                maxRetries + " attempts. Error: " + e.getMessage());
                    // Optionally, update UI or take other actions for persistent failure
                    return; // Give up after max retries
                }
            }
        }

        if (fileData == null) {
            addLogEntry("Could not read file data for create event after " + maxRetries + " retries: " + relativePath + ". Event not sent.");
            return;
        }

        try {
            addLogEntry("File created: " + relativePath + ". Preparing to send event.");
            FileEvent event = new FileEvent(FileEvent.EventType.CREATE, relativePath, fileData);
            sendEventToServer(event); // Send event only after successful read
            // Update modification time only after successful send
            fileModificationTimes.put(relativePath, fullPath.toFile().lastModified());
            SwingUtilities.invokeLater(this::refreshFileList);
            addLogEntry("Create event for " + relativePath + " sent and processed successfully.");
        } catch (Exception e) { // Catch any other unexpected errors from sending or map update
            addLogEntry("Unexpected error after reading file, during create event processing for: " +
                        relativePath + " - " + e.getMessage());
        }
    }

    private void handleModifyEvent(Path fullPath, String relativePath) {
        byte[] fileData = null;
        int maxRetries = 5; // Increased max retries
        long initialDelayMs = 100; // Initial delay
        long currentDelayMs = initialDelayMs;

        // It's crucial to get the modification time *before* attempting to read.
        // If the read takes time, the file might be modified again.
        long lastKnownModTime = fullPath.toFile().lastModified();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                fileData = Files.readAllBytes(fullPath);
                addLogEntry("Successfully read file " + relativePath + " (modify event) on attempt " + attempt);
                break; // Success
            } catch (IOException e) {
                addLogEntry("Attempt " + attempt + "/" + maxRetries + " to read file " + relativePath +
                            " (modify event) failed: " + e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(currentDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        addLogEntry("File read retry for " + relativePath + " interrupted. Aborting modify event.");
                        return;
                    }
                    currentDelayMs *= 2; // Exponential backoff
                } else {
                    addLogEntry("Failed to read file " + relativePath + " (modify event) after " +
                                maxRetries + " attempts. Error: " + e.getMessage());
                    // Optionally, update UI or take other actions for persistent failure
                    return; // Give up after max retries
                }
            }
        }

        if (fileData == null) {
            addLogEntry("Could not read file data for modify event after " + maxRetries + " retries: " + relativePath + ". Event not sent.");
            return;
        }
        
        // Check if the file was modified again *during* our read attempts.
        // This is a simple check; more sophisticated checks might involve checksums if necessary.
        if (fullPath.toFile().lastModified() > lastKnownModTime) {
            addLogEntry("File " + relativePath + " was modified again during read attempts. Re-queueing modify event.");
            // Potentially re-trigger or re-queue the event handling for this file.
            // For simplicity here, we'll just log and proceed, but in a real-world scenario,
            // you might want to re-initiate the handleModifyEvent or add it to a queue.
            // For now, we will proceed with the data we read, but log this occurrence.
        }


        try {
            addLogEntry("File modified: " + relativePath + ". Preparing to send event.");
            FileEvent event = new FileEvent(FileEvent.EventType.MODIFY, relativePath, fileData);
            sendEventToServer(event); // Send event only after successful read
            // Update modification time with the time captured *before* the read attempts,
            // or with the latest if you decide to re-read.
            fileModificationTimes.put(relativePath, lastKnownModTime); // Using the time from before read attempts
            SwingUtilities.invokeLater(this::refreshFileList);
            addLogEntry("Modify event for " + relativePath + " sent and processed successfully.");
        } catch (Exception e) { // Catch any other unexpected errors
            addLogEntry("Unexpected error after reading file, during modify event processing for: " +
                        relativePath + " - " + e.getMessage());
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
        if (!connected || output == null) {
            addLogEntry("Not connected. Cannot send event: " + event.getEventType() + " for " + event.getRelativePath());
            tryReconnect(); // Attempt to reconnect if not connected
            return;
        }
        try {
            addLogEntry("Sending event: " + event.getEventType() + " for " + event.getRelativePath() + " (" + (event.getFileData() != null ? event.getFileData().length : "N/A") + " bytes)");
            output.writeObject(event);
            output.flush();
            addLogEntry("Event sent successfully: " + event.getEventType() + " for " + event.getRelativePath());
        } catch (IOException e) {
            addLogEntry("IOException sending event to server: " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            addLogEntry("Stack trace for IOException: " + sw.toString().substring(0, Math.min(sw.toString().length(), 1000))); // Log first 1000 chars
            tryReconnect(); // Attempt to reconnect on send failure
        } catch (ClassCastException cce) {
            addLogEntry("ClassCastException sending event to server: " + cce.getMessage());
            StringWriter sw = new StringWriter();
            cce.printStackTrace(new PrintWriter(sw));
            addLogEntry("Stack trace for ClassCastException: " + sw.toString().substring(0, Math.min(sw.toString().length(), 1000)));
            tryReconnect();
        } catch (Exception e) {
            addLogEntry("Unexpected error sending event to server: " + e.getClass().getName() + " - " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            addLogEntry("Stack trace for Unexpected error: " + sw.toString().substring(0, Math.min(sw.toString().length(), 1000)));
            tryReconnect();
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
        String logMessage = "[" + timestamp + "] " + message;
        SwingUtilities.invokeLater(() -> {
            logArea.append(logMessage + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
        // Refresh file list after a relevant action is logged
        refreshFileList();
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
        }
        
        SwingUtilities.invokeLater(() -> new FileSyncClientGUI());
    }

    private void disconnectFromServer() {
        addLogEntry("Client [GUI:" + (clientId != null ? clientId.substring(0,8) : "N/A") + "]: Disconnecting from server...");
        connected = false; // Set connected to false immediately

        if (pollingExecutor != null && !pollingExecutor.isShutdown()) {
            addLogEntry("Shutting down polling executor...");
            pollingExecutor.shutdownNow();
            try {
                if (!pollingExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    addLogEntry("Polling executor did not terminate in time.");
                }
            } catch (InterruptedException e) {
                addLogEntry("Interrupted while waiting for polling executor to terminate: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
        pollingExecutor = null;

        if (watchThread != null && watchThread.isAlive()) {
            addLogEntry("Interrupting watch thread...");
            watchThread.interrupt();
            try {
                watchThread.join(1000); // Wait for a short period
                if (watchThread.isAlive()) {
                    addLogEntry("Watch thread did not terminate in time.");
                }
            } catch (InterruptedException e) {
                addLogEntry("Interrupted while waiting for watch thread to join: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
        watchThread = null;

        // Close streams and socket, handling potential nulls and exceptions
        try {
            if (output != null) {
                output.close();
                addLogEntry("Output stream closed.");
            }
        } catch (IOException e) {
            addLogEntry("Error closing output stream: " + e.getMessage());
        } finally {
            output = null;
        }

        try {
            if (input != null) {
                input.close();
                addLogEntry("Input stream closed.");
            }
        } catch (IOException e) {
            addLogEntry("Error closing input stream: " + e.getMessage());
        } finally {
            input = null;
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                addLogEntry("Socket closed.");
            }
        } catch (IOException e) {
            addLogEntry("Error closing socket: " + e.getMessage());
        } finally {
            socket = null;
        }
        
        statusLabel.setText("Not connected");
        // connectButton text and field enablement are handled in toggleConnection
        addLogEntry("Client [GUI:" + (clientId != null ? clientId.substring(0,8) : "N/A") + "]: Disconnected.");
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
        // 1. Clear and repopulate the internal map
        String currentWatchDir = watchDirField.getText();
        File dir = new File(currentWatchDir);

        synchronized (fileModificationTimes) {
            fileModificationTimes.clear();
            if (dir.exists() && dir.isDirectory()) {
                scanDirectoryForFiles(dir, ""); // Populates fileModificationTimes
            }
        }

        // 2. Clear the table
        SwingUtilities.invokeLater(() -> {
            fileTableModel.setRowCount(0);

            // 3. Repopulate the table from the map
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            List<Map.Entry<String, Long>> sortedEntries;
            synchronized (fileModificationTimes) {
                // Sort by path for consistent display
                sortedEntries = new ArrayList<>(fileModificationTimes.entrySet());
                sortedEntries.sort(Map.Entry.comparingByKey());
            }

            for (Map.Entry<String, Long> entry : sortedEntries) {
                String relativePath = entry.getKey();
                File file = new File(currentWatchDir, relativePath.replace('/', File.separatorChar));
                // Check if file still exists before adding to table, as map might be slightly ahead of deletion processing
                if (file.exists()) {
                    fileTableModel.addRow(new Object[]{
                            file.getName(),
                            file.getAbsolutePath(),
                            file.length(),
                            sdf.format(new Date(file.lastModified())),
                    });
                }
            }
        });
    }
}