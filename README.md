# Java File Synchronization System

## Overview

This project implements a file synchronization system similar to Dropbox, built in Java. It allows clients to synchronize a local directory with a central server. Changes made in one client's watched directory (creations, modifications, deletions) are propagated to the server and then to other connected clients.

The system consists of:
*   A **File Sync Server** that manages file storage and coordinates updates between clients.
*   A **File Sync Client** (available as both a GUI and a command-line version) that monitors a local directory and communicates changes to the server.
*   A **File Sync Server GUI** for easy management of the server.

## Authors

*   Gianluca Villegas, gv2114
*   Arjun Dasgupta, ad5648

## Project Context

This project was developed for the Java Final Project, completed on 05-08-2025.

## Features

*   **Client-Server Architecture**: Centralized server manages file versions and client connections.
*   **Real-time File Synchronization**:
    *   **CREATE**: New files in a client's watched directory are uploaded to the server and distributed to other clients.
    *   **MODIFY**: Modifications to existing files are detected, and the updated file is sent to the server and then to other clients.
    *   **DELETE**: Deletions of files in a client's watched directory are propagated to the server and other clients.
*   **Graphical User Interfaces (GUI)**:
    *   `FileSyncClientGUI`: Allows users to configure server connection, watch directory, view synchronized files, and monitor activity logs.
    *   `FileSyncServerGUI`: Allows users to start/stop the server, set the port, and view server activity logs.
*   **Command-Line Client**: `FileSyncClient` provides a non-GUI option for file synchronization, suitable for headless environments or scripting.
*   **Directory Watching**: Utilizes Java NIO `WatchService` for efficient detection of file system changes.
*   **Polling Mechanism**: Includes a polling mechanism in clients as a secondary way to detect file modifications, enhancing reliability.
*   **Client Identification**: Each client instance (GUI or CLI) is assigned a unique ID for tracking and to prevent echoing events back to the source client.
*   **Server File Manifest**: The server maintains a manifest of known files. During the initial handshake, the client receives this manifest to determine which local files need to be sent to the server.
*   **Connection Management**: Clients attempt to reconnect if the connection to the server is lost.
*   **Event-Driven Communication**: File changes are encapsulated as `FileEvent` objects (CREATE, MODIFY, DELETE) and transmitted between client and server using Java Object Serialization over TCP/IP sockets.

## Components

1.  **`FileEvent.java`**:
    *   A serializable class representing a file operation (CREATE, MODIFY, DELETE).
    *   Contains the event type, relative path of the file, and file data (for CREATE/MODIFY).

2.  **`FileSyncServer.java`**:
    *   The core server logic.
    *   Listens for client connections on a specified port.
    *   Manages multiple `ClientHandler` threads, one for each connected client.
    *   Stores synchronized files in a designated server directory (default: `server_files`).
    *   Receives `FileEvent` objects from clients, applies changes to its local file store, and broadcasts these events to other connected clients.
    *   Maintains a map of connected clients by their unique client IDs.

3.  **`FileSyncServerGUI.java`**:
    *   A Swing-based GUI to manage the `FileSyncServer`.
    *   Allows starting and stopping the server and setting the listening port.
    *   Displays server logs.

4.  **`FileSyncClient.java`**:
    *   A command-line client application.
    *   Connects to the `FileSyncServer`.
    *   Watches a specified local directory (default: `client_files`) for changes.
    *   Sends `FileEvent` objects to the server upon detecting local file changes.
    *   Receives `FileEvent` objects from the server and applies them to its local watched directory.
    *   Performs an initial synchronization with the server based on the server's file manifest.

5.  **`FileSyncClientGUI.java`**:
    *   A Swing-based GUI for the client application.
    *   Provides user-friendly controls for connecting to the server, specifying the host, port, and watch directory.
    *   Displays a table of synchronized files with their status.
    *   Shows a log of client activity.
    *   Includes functionality to manually add files to the watched directory.
    *   Internally uses similar synchronization logic as `FileSyncClient`.

## How to Run

1.  **Compile**: Compile all `.java` files.
    ```bash
    javac *.java
    ```

2.  **Run the Server**:
    *   **Using GUI**:
        ```bash
        java FileSyncServerGUI
        ```
        Enter the desired port (default is 8000) and click "Start Server".
    *   **Command Line (headless server)**:
        ```bash
        java FileSyncServer [port]
        ```
        If `[port]` is not specified, it defaults to 8000. The server stores files in the `server_files` directory (created automatically if it doesn't exist).

3.  **Run the Client**:
    *   **Using GUI**:
        ```bash
        java FileSyncClientGUI
        ```
        Configure the server host, port, and the local directory to watch (default: `client_files`). Click "Connect".
    *   **Command Line**:
        ```bash
        java FileSyncClient
        ```
        The command-line client uses hardcoded server host (`localhost`), port (`8000`), and watches the `client_files` directory. These can be modified in the `FileSyncClient.java` source code if needed.

## Communication Protocol

*   **Transport**: TCP/IP Sockets.
*   **Serialization**: Java Object Serialization is used to transmit:
    *   `FileEvent` objects between client and server.
    *   Client IDs from client to server during handshake.
    *   Server file manifest (a `Set<String>` of relative file paths) from server to client during handshake.

## Directory Structure

*   **`server_files/`**: Default directory on the server side where synchronized files are stored.
*   **`client_files/`**: Default directory on the client side that is watched for synchronization.

Both directories are created automatically if they do not exist when the server or client starts.