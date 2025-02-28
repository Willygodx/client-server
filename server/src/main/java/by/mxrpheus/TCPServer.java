package by.mxrpheus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class TCPServer {

    private static final String FILES_DIRECTORY = "server\\files";

    private final Map<String, FileTransferInfo> uploadsInfo = new HashMap<>();
    private final Map<String, FileTransferInfo> downloadsInfo = new HashMap<>();

    public static void main(String[] args) {
        TCPServer server = new TCPServer();
        server.start();
    }

    public void start() {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Please, enter port: ");
        int port = scanner.nextInt();

        try (Selector selector = Selector.open();
             ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {

            serverSocketChannel.bind(new InetSocketAddress(port));
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("Server started on port: " + port);

            while (true) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();

                    if (key.isAcceptable()) {
                        handleAccept(serverSocketChannel, selector);
                    }

                    if (key.isReadable()) {
                        handleRead(key);
                    }

                    iter.remove();
                }
            }

        } catch (IOException e) {
            System.out.println("Server launch error: " + e.getMessage());
        }
    }

    private void handleAccept(ServerSocketChannel serverSocketChannel, Selector selector) throws IOException {
        SocketChannel clientChannel = serverSocketChannel.accept();
        clientChannel.configureBlocking(false);
        clientChannel.socket().setKeepAlive(true);
        clientChannel.register(selector, SelectionKey.OP_READ);
        System.out.println("Client connected: " + clientChannel.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead;

        try {
            bytesRead = clientChannel.read(buffer);

            if (bytesRead == -1) {
                System.out.println("Client " + clientChannel.getRemoteAddress() + " disconnected.");
                clientChannel.close();
                return;
            }

            buffer.flip();
            String inputLine = new String(buffer.array(), 0, bytesRead).trim();
            System.out.println("Received: " + inputLine);

            if (inputLine.startsWith("ECHO")) {
                handleEchoCommand(inputLine, clientChannel);
            } else if (inputLine.equalsIgnoreCase("TIME")) {
                handleTimeCommand(clientChannel);
            } else if (inputLine.startsWith("UPLOAD")) {
                handleUploadCommand(inputLine, clientChannel);
            } else if (inputLine.startsWith("DOWNLOAD")) {
                handleDownloadCommand(inputLine, clientChannel);
            } else if (isExitCommand(inputLine)) {
                clientChannel.write(ByteBuffer.wrap("Connection closed.".getBytes()));
                System.out.println("Client " + clientChannel.getRemoteAddress() + " disconnected.");
                clientChannel.close();
            } else {
                clientChannel.write(ByteBuffer.wrap("Unknown command.".getBytes()));
            }
        } catch (IOException e) {
            try {
                System.out.println("Client " + clientChannel.getRemoteAddress() + " disconnected unexpectedly: " + e.getMessage());
                clientChannel.close();
            } catch (IOException ex) {
                System.out.println("Error closing client channel: " + ex.getMessage());
            }
        }
    }

    private void handleDownloadCommand(String inputLine, SocketChannel clientChannel) throws IOException{
        String filename = inputLine.substring(9);
        File file = new File(FILES_DIRECTORY, filename);

        if (!file.exists() || !file.isFile()) {
            try {
                clientChannel.write(ByteBuffer.wrap(("File " + filename + " not found!").getBytes()));
            } catch (IOException e) {
                System.out.println("Error sending file not found message: " + e.getMessage());
            }
            return;
        }

        String clientId = clientChannel.socket().getInetAddress().toString();
        FileTransferInfo downloadInfo = downloadsInfo.get(clientId);

        long startPosition = 0;
        ByteBuffer positionBuffer = ByteBuffer.allocate(8);
        if (downloadInfo != null && downloadInfo.getFilename().equals(filename)) {
            positionBuffer.putLong(1L);
            positionBuffer.flip();
            while (positionBuffer.hasRemaining()) {
                clientChannel.write(positionBuffer);
            }
        } else {
            positionBuffer.putLong(0L);
            positionBuffer.flip();
            while (positionBuffer.hasRemaining()) {
                clientChannel.write(positionBuffer);
            }
            downloadsInfo.put(clientId, new FileTransferInfo(filename, 0L));
        }

        ByteBuffer filePosition = ByteBuffer.allocate(8);
        while (filePosition.hasRemaining()) {
            clientChannel.read(filePosition);
        }
        filePosition.flip();
        startPosition = filePosition.getLong();

        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            fileInputStream.skip(startPosition);
            ByteBuffer sizeBuffer = ByteBuffer.allocate(8);
            sizeBuffer.putLong(file.length());
            sizeBuffer.flip();
            while (sizeBuffer.hasRemaining()) {
                clientChannel.write(sizeBuffer);
            }

            ByteBuffer buffer = ByteBuffer.allocate(4096);
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer.array())) != -1) {
                buffer.clear();
                buffer.limit(bytesRead);
                while (buffer.hasRemaining()) {
                    clientChannel.write(buffer);
                }
                downloadsInfo.get(clientId).setBytesTransferred(downloadsInfo.get(clientId).getBytesTransferred() + bytesRead);
            }

            System.out.println("File sent: " + file.getAbsolutePath());
            downloadsInfo.remove(clientId);
        } catch (IOException e) {
            System.out.println("Error sending file: " + e.getMessage());
        }
    }

    private void handleUploadCommand(String inputLine, SocketChannel clientChannel) throws IOException {
        String fullPath = inputLine.substring(7);
        String filename = Paths.get(fullPath).getFileName().toString();
        File file = new File(FILES_DIRECTORY, filename);

        String clientId = clientChannel.socket().getInetAddress().toString();
        FileTransferInfo uploadInfo = uploadsInfo.get(clientId);

        long filePosition = 0;
        if (uploadInfo != null && uploadInfo.getFilename().equals(filename)) {
            filePosition = file.length();
        } else {
            uploadsInfo.put(clientId, new FileTransferInfo(filename, 0L));
        }

        ByteBuffer positionBuffer = ByteBuffer.allocate(8);
        positionBuffer.putLong(filePosition);
        positionBuffer.flip();
        while (positionBuffer.hasRemaining()) {
            clientChannel.write(positionBuffer);
        }

        try (FileOutputStream fileOutputStream = new FileOutputStream(file, true)) {
            ByteBuffer sizeBuffer = ByteBuffer.allocate(8);
            while (sizeBuffer.hasRemaining()) {
                clientChannel.read(sizeBuffer);
            }
            sizeBuffer.flip();
            long fileSize = sizeBuffer.getLong();

            ByteBuffer buffer = ByteBuffer.allocate(4096);
            long totalBytesRead = filePosition;
            int bytesRead;

            while (totalBytesRead < fileSize && (bytesRead = clientChannel.read(buffer)) != -1) {
                buffer.flip();
                fileOutputStream.getChannel().write(buffer);
                totalBytesRead += bytesRead;
                buffer.clear();
            }

            System.out.println("File uploaded: " + file.getAbsolutePath());
            uploadsInfo.remove(clientId);
        } catch (IOException e) {
            System.out.println("Client interaction error: " + e.getMessage());
        }
    }

    private void handleEchoCommand(String inputLine, SocketChannel clientChannel) throws IOException {
        if (inputLine.length() > 5) {
            String response = inputLine.substring(5);
            clientChannel.write(ByteBuffer.wrap(response.getBytes()));
        } else {
            clientChannel.write(ByteBuffer.wrap("ECHO without data".getBytes()));
        }
    }

    private void handleTimeCommand(SocketChannel clientChannel) throws IOException {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        clientChannel.write(ByteBuffer.wrap(("Server time: " + time).getBytes()));
    }

    private boolean isExitCommand(String command) {
        return command.equalsIgnoreCase("CLOSE") ||
                command.equalsIgnoreCase("EXIT") ||
                command.equalsIgnoreCase("QUIT");
    }
}