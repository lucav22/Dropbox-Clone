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
        splitPane.setResizeWeight(0.7); // 70% to top component
        
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
}