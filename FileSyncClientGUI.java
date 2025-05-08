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
}