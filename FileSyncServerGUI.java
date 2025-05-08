import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class FileSyncServerGUI extends JFrame {
    private JTextArea logArea;
    private JButton startButton;
    private JButton stopButton;
    private JTextField portField;
    private JLabel statusLabel;
    private FileSyncServer server;
    private Thread serverThread;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public FileSyncServerGUI() {
        setTitle("File Sync Server");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Correct constant
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopServer();
                dispose();
                System.exit(0);
            }
        });

        initComponents();
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Control Panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(new JLabel("Port:"));
        portField = new JTextField(String.valueOf(8000), 5); // Default port
        controlPanel.add(portField);

        startButton = new JButton("Start Server");
        startButton.addActionListener(e -> startServer());
        controlPanel.add(startButton);

        stopButton = new JButton("Stop Server");
        stopButton.addActionListener(e -> stopServer());
        stopButton.setEnabled(false);
        controlPanel.add(stopButton);
        mainPanel.add(controlPanel, BorderLayout.NORTH);

        // Log Area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); // Correct Font usage
        JScrollPane scrollPane = new JScrollPane(logArea);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Status Label
        statusLabel = new JLabel("Server not running.");
        statusLabel.setBorder(BorderFactory.createEtchedBorder());
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port number.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        server = new FileSyncServer(port);
        server.setGui(this); // Link server logic to this GUI

        serverThread = new Thread(server::start);
        serverThread.start();

        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        portField.setEnabled(false);
        statusLabel.setText("Server running on port " + port + ". Files in: " + new File(FileSyncServer.SERVER_FILES_DIR).getAbsolutePath());
    }

    private void stopServer() {
        if (server != null) {
            server.stop(); // This will also close the server socket and stop the thread
        }
        if (serverThread != null && serverThread.isAlive()) {
            try {
                serverThread.join(1000); // Wait for server thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                addLogEntry("Error waiting for server thread to stop: " + e.getMessage());
            }
        }
        serverThread = null;
        server = null;

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        portField.setEnabled(true);
        statusLabel.setText("Server stopped.");
        addLogEntry("Server has been stopped by GUI action.");
    }

    public void addLogEntry(String message) {
        String timestamp = timeFormat.format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (UnsupportedLookAndFeelException | ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            System.err.println("Error setting Look and Feel: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> new FileSyncServerGUI().setVisible(true));
    }
}
