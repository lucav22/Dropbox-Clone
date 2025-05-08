import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.LinkedList;
import static java.nio.file.StandardWatchEventKinds.*;

public class FileSyncClientGUI extends JFrame {
    private String serverHost = "localhost";
    private int serverPort = 8000;
    private String watchDir = "client_files";
    private Map<String, Long> fileModificationTimes = new HashMap<>();

    private Socket socket;
    private ObjectOutputStream output;
    private ObjectInputStream input;
    private boolean connected = false;
    private Thread watchThread;

    private JTextField serverHostField;
    private JTextField serverPortField;
    private JTextField watchDirField;
    private JButton connectButton;
    private JButton browseButton;
    private JTextArea logArea;
    private JTable fileTable;
    private DefaultTableModel fileTableModel;
    private JLabel statusLabel;

    private List<String> activityLog = new ArrayList<>();
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

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

    private void registerAll(Path start, WatchService watchService) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void pollForModifications() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000);
                
                Map<String, Long> fileMapCopy = new HashMap<>(fileModificationTimes);
                
                for (Map.Entry<String, Long> entry : fileMapCopy.entrySet()) {
                    String filePath = entry.getKey();
                    long lastModifiedTime = entry.getValue();
                    
                    File file = new File(watchDir + File.separator + filePath);
                    
                    if (file.exists() && file.isFile()) {
                        long currentModifiedTime = file.lastModified();
                        
                        if (currentModifiedTime > lastModifiedTime) {
                            fileModificationTimes.put(filePath, currentModifiedTime);
                            SwingUtilities.invokeLater(() -> handleModifyEvent(file.toPath(), filePath));
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
        }
    }

    private void handleCreateEvent(Path fullPath, String relativePath) {
        try {
            Thread.sleep(100);
            
            addLogEntry("File created: " + relativePath);
            byte[] fileData = Files.readAllBytes(fullPath);
            FileEvent event = new FileEvent(FileEvent.EventType.CREATE, relativePath, fileData);
            sendEventToServer(event);
            fileModificationTimes.put(relativePath, fullPath.toFile().lastModified());
            SwingUtilities.invokeLater(this::refreshFileList);
        } catch (Exception e) {
            addLogEntry("Error handling create event: " + e.getMessage());
        }
    }
    
    private void handleModifyEvent(Path fullPath, String relativePath) {
        try {
            addLogEntry("File modified: " + relativePath);
            byte[] fileData = Files.readAllBytes(fullPath);
            FileEvent event = new FileEvent(FileEvent.EventType.MODIFY, relativePath, fileData);
            sendEventToServer(event);
            SwingUtilities.invokeLater(this::refreshFileList);
        } catch (Exception e) {
            addLogEntry("Error handling modify event: " + e.getMessage());
        }
    }
    
    private void handleDeleteEvent(String relativePath) {
        try {
            addLogEntry("File deleted: " + relativePath);
            FileEvent event = new FileEvent(FileEvent.EventType.DELETE, relativePath, null);
            sendEventToServer(event);
            fileModificationTimes.remove(relativePath);
            SwingUtilities.invokeLater(this::refreshFileList);
        } catch (Exception e) {
            addLogEntry("Error handling delete event: " + e.getMessage());
        }
    }
}