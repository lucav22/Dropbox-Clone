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

* **Client-Server Architecture**: Centralized server manages file versions and client connections.
* **Real-time File Synchronization**:
  * **CREATE**: New files in a client's watched directory are uploaded to the server and distributed to other clients.
  * **MODIFY**: Modifications to existing files are detected, and the updated file is sent to the server and then to other clients.
  * **DELETE**: Deletions of files in a client's watched directory are propagated to the server and other clients.
* **Graphical User Interfaces (GUI)**:
  * `FileSyncClientGUI`: Allows users to configure server connection, watch directory, view synchronized files, and monitor activity logs.
  * `FileSyncServerGUI`: Allows users to start/stop the server, set the port, and view server activity logs.
* **Command-Line Client**: `FileSyncClient` provides a non-GUI option for file synchronization, suitable for headless environments or scripting.
* **Directory Watching**: Utilizes Java NIO `WatchService` for efficient detection of file system changes.
* **Polling Mechanism**: Includes a polling mechanism in clients as a secondary way to detect file modifications, enhancing reliability.
* **Client Identification**: Each client instance (GUI or CLI) is assigned a unique ID (specifically, a UUID string) for tracking and to prevent echoing events back to the source client.
* **Initial Synchronization**: Upon connecting, a client performs a detailed handshake and synchronization with the server:
  1. The client sends its unique ID (UUID string) to the server.
  2. The server acknowledges the client and then sends a stream of `FileEvent` objects (typically `CREATE` events containing full file data) for all files it currently manages. This effectively transmits the server's current file manifest to the client.
  3. After sending all its file events, the server sends a special string object "INITIAL_SYNC_COMPLETE" to signal the end of its initial file transmission.
  4. The client receives these `FileEvent` objects and the completion marker. It applies the events to its local directory, creating or updating files as necessary to match the server's state.
  5. Once the client has processed all incoming events from the server and received the "INITIAL_SYNC_COMPLETE" signal, it then scans its own watched directory. It compares its local files against the set of files it just received from the server. For any local files that were not part of the server's initial transmission (i.e., files unique to the client or created/modified locally before this session), the client sends corresponding `FileEvent` objects (CREATE or MODIFY) to the server.
  This comprehensive two-way process ensures that both the client and server directories become consistent with each other at the beginning of a synchronization session.
* **Connection Management**: Clients attempt to reconnect if the connection to the server is lost.
* **Event-Driven Communication**: File changes are encapsulated as `FileEvent` objects (CREATE, MODIFY, DELETE) and transmitted between client and server using Java Object Serialization over TCP/IP sockets for ongoing synchronization after the initial handshake.

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

* **Transport**: TCP/IP Sockets.
* **Serialization**: Java Object Serialization is used to transmit:
  * The client's unique ID (a UUID String) from the client to the server as the first message upon connection.
  * During the initial handshake, after receiving the client ID, the server sends a sequence of `FileEvent` objects to the client. Each `FileEvent` represents a file currently managed by the server.
  * Following the stream of `FileEvent`s from the server, a special String object "INITIAL_SYNC_COMPLETE" is sent by the server to the client to mark the end of the server's initial file transmission.
  * After the client processes the server's initial files and receives the "INITIAL_SYNC_COMPLETE" marker, the client may send its own `FileEvent` objects to the server for any files it has that the server doesn't.
  * For ongoing synchronization (after the initial handshake is fully completed), `FileEvent` objects are exchanged between client and server to reflect real-time creations, modifications, or deletions.

## Directory Structure

*   **`server_files/`**: Default directory on the server side where synchronized files are stored.
*   **`client_files/`**: Default directory on the client side that is watched for synchronization.

Both directories are created automatically if they do not exist when the server or client starts.