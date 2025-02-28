package by.mxrpheus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Scanner;

public class TCPClient {

    private static final String FILES_DIRECTORY = "client/files";

    public static void main(String[] args) {
        TCPClient client = new TCPClient();
        client.start();
    }

    public void start() {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter IP: ");
        String host = scanner.nextLine();
        System.out.print("Enter port: ");
        int port = Integer.parseInt(scanner.nextLine());

        try (SocketChannel socketChannel = SocketChannel.open()) {
            socketChannel.connect(new InetSocketAddress(host, port));
            System.out.println("Connected to server " + host + ":" + port);

            interactWithServer(scanner, socketChannel);

        } catch (IOException e) {
            System.out.println("I/O exception: " + e.getMessage());
        }
    }

    private void interactWithServer(Scanner scanner, SocketChannel socketChannel) throws IOException {
        System.out.println("Enter command (ECHO, TIME, CLOSE):");

        String userInput;
        while (true) {
            System.out.print("-> ");
            userInput = scanner.nextLine();

            ByteBuffer buffer = ByteBuffer.wrap(userInput.getBytes());
            socketChannel.write(buffer);

            if (isExitCommand(userInput)) {
                System.out.println("Client shutdown.");
                break;
            }

            if (userInput.startsWith("UPLOAD")) {
                handleUploadCommand(userInput, socketChannel);
            } else if (userInput.startsWith("DOWNLOAD")) {
                handleDownloadCommand(userInput, socketChannel);
            } else {
                ByteBuffer responseBuffer = ByteBuffer.allocate(1024);
                int bytesRead = socketChannel.read(responseBuffer);
                if (bytesRead == -1) {
                    System.out.println("Server connection closed.");
                    break;
                }
                responseBuffer.flip();
                String serverResponse = new String(responseBuffer.array(), 0, bytesRead).trim();
                System.out.println("Server response: " + serverResponse);
            }
        }
    }

    private void handleDownloadCommand(String userInput, SocketChannel socketChannel) throws IOException {
        String filename = userInput.substring(9);
        File file = new File(FILES_DIRECTORY, filename);

        long startTime = System.currentTimeMillis();

        ByteBuffer positionBuffer = ByteBuffer.allocate(8);
        while (positionBuffer.hasRemaining()) {
            socketChannel.read(positionBuffer);
        }
        positionBuffer.flip();
        long positionCode = positionBuffer.getLong();

        long filePosition = 0;
        if (file.exists()) {
            if (positionCode == 1L) {
                filePosition = file.length();
            }
        }

        ByteBuffer serverPosition = ByteBuffer.allocate(8);
        serverPosition.putLong(filePosition);
        serverPosition.flip();
        while (serverPosition.hasRemaining()) {
            socketChannel.write(serverPosition);
        }

        try (FileOutputStream fileOutputStream = new FileOutputStream(file, true)) {
            ByteBuffer sizeBuffer = ByteBuffer.allocate(8);
            while (sizeBuffer.hasRemaining()) {
                socketChannel.read(sizeBuffer);
            }
            sizeBuffer.flip();
            long fileSize = sizeBuffer.getLong();

            ByteBuffer buffer = ByteBuffer.allocate(4096);
            long totalBytesRead = filePosition;
            int bytesRead;

            while (totalBytesRead < fileSize && (bytesRead = socketChannel.read(buffer)) != -1) {
                buffer.flip();
                fileOutputStream.getChannel().write(buffer);
                totalBytesRead += bytesRead;
                buffer.clear();
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            double bitrateMB = ((fileSize * 8) / (duration / 1000.0)) / (8 * 1024 * 1024);

            System.out.println("File downloaded: " + file.getAbsolutePath());
            System.out.printf("Download bitrate: %.2f MB/s%n", bitrateMB);
        } catch (IOException e) {
            System.out.println("Error downloading file: " + e.getMessage());
        }
    }

    private void handleUploadCommand(String userInput, SocketChannel socketChannel) throws IOException {
        String filename = userInput.substring(7);
        File file = new File(filename);

        if (!file.exists()) {
            System.out.println("File not found!");
            return;
        }

        long startTime = System.currentTimeMillis();

        ByteBuffer positionBuffer = ByteBuffer.allocate(8);
        while (positionBuffer.hasRemaining()) {
            socketChannel.read(positionBuffer);
        }
        positionBuffer.flip();

        long startPosition = positionBuffer.getLong();

        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            fileInputStream.skip(startPosition);
            ByteBuffer sizeBuffer = ByteBuffer.allocate(8);
            sizeBuffer.putLong(file.length());
            sizeBuffer.flip();
            while (sizeBuffer.hasRemaining()) {
                socketChannel.write(sizeBuffer);
            }

            ByteBuffer buffer = ByteBuffer.allocate(4096);
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer.array())) != -1) {
                buffer.clear();
                buffer.limit(bytesRead);
                while (buffer.hasRemaining()) {
                    socketChannel.write(buffer);
                }
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            double bitrateMB = ((file.length() * 8) / (duration / 1000.0)) / (8 * 1024 * 1024);

            System.out.println("File uploaded: " + filename);
            System.out.printf("Upload bitrate: %.2f MB/s%n", bitrateMB);
        } catch (IOException e) {
            System.out.println("Server interaction error: " + e.getMessage());
        }
    }

    private boolean isExitCommand(String command) {
        return command.equalsIgnoreCase("CLOSE") ||
                command.equalsIgnoreCase("EXIT") ||
                command.equalsIgnoreCase("QUIT");
    }
}