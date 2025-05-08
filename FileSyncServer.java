import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class FileSyncServer {
    public static final int DEFAULT_PORT = 8000; // Made public for GUI access
    public static final String SERVER_FILES_DIR = "server_files"; // Made public for GUI access
    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService clientHandlerPool;
    private final Set<ClientHandler> clientHandlers = ConcurrentHashMap.newKeySet();
    private FileSyncServerGUI gui; // Optional GUI

    public FileSyncServer(int port) {
        this.port = port;
        File serverDir = new File(SERVER_FILES_DIR);
        if (!serverDir.exists()) {
            serverDir.mkdirs();
        }
        this.clientHandlerPool = Executors.newCachedThreadPool();
    }

    public void setGui(FileSyncServerGUI gui) {
        this.gui = gui;
    }

    private void log(String message) {
        if (gui != null) {
            gui.addLogEntry(message);
        } else {
            System.out.println(message);
        }
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            log("Server started on port: " + port);
            log("Server files directory: " + new File(SERVER_FILES_DIR).getAbsolutePath());

            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    log("New client connected: " + clientSocket.getInetAddress());
                    ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                    clientHandlers.add(clientHandler);
                    clientHandlerPool.submit(clientHandler);
                } catch (SocketException se) { // More specific exception
                    if (serverSocket.isClosed()) {
                        log("Server socket closed, stopping accepting new clients.");
                    } else {
                        log("SocketException while accepting client connection: " + se.getMessage());
                    }
                } catch (IOException e) {
                    if (serverSocket.isClosed()) {
                        log("Server socket closed, stopping accepting new clients (IOException).");
                    } else {
                        log("IOException accepting client connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log("Could not start server on port " + port + ": " + e.getMessage());
        } finally {
            // stop(); // Avoid calling stop() here as it might lead to premature shutdown in some cases
        }
    }

    public void stop() {
        log("Stopping server...");
        clientHandlerPool.shutdown();
        try {
            if (!clientHandlerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                clientHandlerPool.shutdownNow();
            }
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            for (ClientHandler handler : clientHandlers) {
                handler.closeConnection();
            }
            clientHandlers.clear();
            log("Server stopped.");
        } catch (IOException e) {
            log("Error closing server socket: " + e.getMessage());
        } catch (InterruptedException e) {
            log("Server stop interrupted: " + e.getMessage());
            clientHandlerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    void removeClient(ClientHandler clientHandler) {
        clientHandlers.remove(clientHandler);
        log("Client disconnected: " + clientHandler.getClientAddress());
    }

    synchronized void handleFileEvent(FileEvent event, ClientHandler sourceHandler) {
        String relativePathFromClient = event.getRelativePath();
        // Normalize the relative path to use the system's file separator
        String relativePath = relativePathFromClient.replace('/', File.separatorChar);
        String fullPath = SERVER_FILES_DIR + File.separator + relativePath;
        File file = new File(fullPath);

        log("Received event: " + event.getEventType() + " for " + relativePathFromClient + " (normalized to " + relativePath + ") from " + sourceHandler.getClientAddress());

        try {
            switch (event.getEventType()) {
                case CREATE:
                case MODIFY:
                    File parentDir = file.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        if (parentDir.mkdirs()) {
                            log("Created parent directory: " + parentDir.getAbsolutePath());
                        } else {
                            log("Failed to create parent directory: " + parentDir.getAbsolutePath());
                            // Optionally, throw an exception or return if directory creation is critical
                        }
                    }
                    Files.write(file.toPath(), event.getFileData(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    log("File " + event.getEventType().name().toLowerCase() + "d: " + relativePath);
                    broadcastEvent(event, sourceHandler);
                    break;
                case DELETE:
                    if (file.exists()) {
                        Files.delete(file.toPath());
                        log("File deleted: " + relativePath);
                        broadcastEvent(event, sourceHandler);
                    } else {
                        log("File delete event for non-existing file: " + relativePath);
                    }
                    break;
            }
        } catch (IOException e) {
            log("Error processing file event for " + relativePath + ": " + e.getMessage());
        }
    }

    private void broadcastEvent(FileEvent event, ClientHandler sourceHandler) {
        for (ClientHandler handler : clientHandlers) {
            if (handler != sourceHandler) {
                handler.sendFileEvent(event);
            }
        }
    }

    public static void main(String[] args) {
        int portArg = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                portArg = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0] + ". Using default port " + DEFAULT_PORT);
            }
        }
        FileSyncServer server = new FileSyncServer(portArg);
        server.start();
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final FileSyncServer server;
        private ObjectInputStream input;
        private ObjectOutputStream output;
        private final String clientAddress;
        private volatile boolean streamsInitialized = false;

        public ClientHandler(Socket socket, FileSyncServer server) {
            this.clientSocket = socket;
            this.server = server;
            this.clientAddress = socket.getInetAddress().toString();
            // Stream initialization moved to run() to avoid overridable method call issues
            // and to handle exceptions more gracefully within the thread.
        }

        private boolean initializeStreams() {
            try {
                this.output = new ObjectOutputStream(clientSocket.getOutputStream());
                this.input = new ObjectInputStream(clientSocket.getInputStream());
                streamsInitialized = true;
                return true;
            } catch (IOException e) {
                this.server.log("Error setting up streams for client " + this.clientAddress + ": " + e.getMessage());
                return false;
            }
        }

        public String getClientAddress() {
            return clientAddress;
        }

        public void sendFileEvent(FileEvent event) {
            if (!streamsInitialized || output == null) {
                server.log("Cannot send event, streams not initialized for " + clientAddress);
                return;
            }
            try {
                output.writeObject(event);
                output.flush();
                server.log("Sent event " + event.getEventType() + " for " + event.getRelativePath() + " to " + clientAddress);
            } catch (IOException e) {
                server.log("Error sending event to client " + clientAddress + ": " + e.getMessage());
            }
        }

        @Override
        public void run() {
            if (!initializeStreams()) {
                server.log("Client handler for " + clientAddress + " failed to initialize streams. Closing.");
                closeConnection();
                server.removeClient(this);
                return;
            }

            try {
                sendExistingFilesToClient();

                Object receivedObject;
                while (clientSocket.isConnected() && !clientSocket.isClosed() && (receivedObject = input.readObject()) != null) {
                    if (receivedObject instanceof FileEvent castedEvent) {
                        server.handleFileEvent(castedEvent, this);
                        output.writeObject("ACK: Event " + castedEvent.getEventType() + " for " + castedEvent.getRelativePath() + " received.");
                        output.flush();
                    } else {
                        if (receivedObject != null) {
                            server.log("Received unknown object type from " + clientAddress + ": " + receivedObject.getClass().getName());
                        } else {
                             server.log("Received null object from " + clientAddress + ", client likely disconnected.");
                             break; // Exit loop if null is read, indicating stream closure
                        }
                    }
                }
            } catch (EOFException e) {
                server.log("Client " + clientAddress + " disconnected (EOF).");
            } catch (SocketException e) {
                server.log("Client " + clientAddress + " connection reset/closed: " + e.getMessage());
            } catch (IOException e) {
                server.log("IOException with client " + clientAddress + ": " + e.getMessage());
            } catch (ClassNotFoundException e) {
                 server.log("ClassNotFoundException from client " + clientAddress + ": " + e.getMessage());
            } finally {
                closeConnection();
                server.removeClient(this);
            }
        }

        private void sendExistingFilesToClient() {
            if (!streamsInitialized) return;
            File baseDir = new File(SERVER_FILES_DIR);
            if (baseDir.exists() && baseDir.isDirectory()) {
                sendFilesRecursively(baseDir, "");
            }
            server.log("Initial file list sent to client " + clientAddress);
        }

        private void sendFilesRecursively(File currentDir, String relativePath) {
            File[] filesInDir = currentDir.listFiles();
            if (filesInDir == null) return;

            for (File file : filesInDir) {
                String newRelativePath = relativePath.isEmpty() ? file.getName() : relativePath + File.separator + file.getName();
                if (file.isDirectory()) {
                    sendFilesRecursively(file, newRelativePath);
                } else {
                    try {
                        byte[] fileData = Files.readAllBytes(file.toPath());
                        FileEvent event = new FileEvent(FileEvent.EventType.CREATE, newRelativePath, fileData);
                        sendFileEvent(event);
                    } catch (IOException e) {
                        server.log("Error reading or sending initial file " + newRelativePath + " to " + clientAddress + ": " + e.getMessage());
                    }
                }
            }
        }

        public void closeConnection() {
            try {
                if (input != null) input.close();
                if (output != null) output.close();
                if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
            } catch (IOException e) {
                server.log("Error closing connection for client " + clientAddress + ": " + e.getMessage());
            }
        }
    }
}
