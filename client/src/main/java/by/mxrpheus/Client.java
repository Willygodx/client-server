package by.mxrpheus;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class Client {
    private static final int PACKET_SIZE = 1024;
    private static final int HEADER_SIZE = 9;
    private static final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE;
    private static final int WINDOW_SIZE = 5;
    private static final long TIMEOUT_MS = 500;

    // Папка для хранения файлов на клиенте
    private static final String CLIENT_DIR = "client_files";

    private DatagramChannel channel;
    private InetSocketAddress serverAddress;

    public static void main(String[] args) {
        new Client().start();
    }

    public void start() {
        try {
            Scanner scanner = new Scanner(System.in);
            // Создаем папку для файлов клиента, если не существует
            File clientDir = new File(CLIENT_DIR);
            if (!clientDir.exists()) {
                clientDir.mkdirs();
            }
            // Запрос IP и порта сервера у пользователя
            System.out.print("Введите IP сервера: ");
            String ip = scanner.nextLine().trim();
            System.out.print("Введите порт сервера: ");
            int port = Integer.parseInt(scanner.nextLine().trim());
            serverAddress = new InetSocketAddress(ip, port);

            channel = DatagramChannel.open();
            channel.configureBlocking(false);

            while (true) {
                System.out.print("Введите команду: ");
                String line = scanner.nextLine();
                String[] parts = line.split(" ");
                String command = parts[0].toUpperCase();
                if(command.equals("UPLOAD")) {
                    if(parts.length < 2) {
                        System.out.println("Не указан файл для загрузки");
                        continue;
                    }
                    String filename = parts[1];
                    int offset = 0;
                    if(parts.length >= 3) {
                        try { offset = Integer.parseInt(parts[2]); } catch(NumberFormatException e) { offset = 0; }
                    }
                    uploadFile(filename, offset);
                } else if(command.equals("DOWNLOAD")) {
                    if(parts.length < 2) {
                        System.out.println("Не указан файл для скачивания");
                        continue;
                    }
                    String filename = parts[1];
                    int offset = 0;
                    if(parts.length >= 3) {
                        try { offset = Integer.parseInt(parts[2]); } catch(NumberFormatException e) { offset = 0; }
                    }
                    downloadFile(filename, offset);
                } else {
                    sendCommand("CMD:" + line);
                    String response = receiveCommand();
                    System.out.println("Ответ сервера: " + response);
                    if(command.equals("CLOSE")) break;
                }
            }
            channel.close();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    // Отправка управляющей команды серверу
    private void sendCommand(String cmd) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(cmd.getBytes());
        channel.send(buf, serverAddress);
    }

    // Получение управляющего сообщения от сервера
    private String receiveCommand() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(PACKET_SIZE);
        long start = System.currentTimeMillis();
        while(System.currentTimeMillis() - start < 2000) {
            SocketAddress addr = channel.receive(buf);
            if(addr != null) {
                buf.flip();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                String resp = new String(data);
                if(resp.startsWith("CMD:")) {
                    return resp.substring(4).trim();
                }
            }
        }
        return "No response";
    }

    // Загрузка файла (UPLOAD) с выводом прогресса передачи пакетов
    private void uploadFile(String filename, int offset) {
        try {
            sendCommand("CMD:UPLOAD " + filename + " " + offset);
            String response = receiveCommand();
            if(!response.startsWith("READY_FOR_UPLOAD")) {
                System.out.println("Ошибка: " + response);
                return;
            }
            // Файл для загрузки берется из папки CLIENT_DIR
            File file = new File(CLIENT_DIR, filename);
            if(!file.exists()){
                System.out.println("Файл не найден: " + file.getAbsolutePath());
                return;
            }
            byte[][] packets = createPackets(file, offset);
            int base = offset;
            int totalPackets = packets.length + offset;
            Map<Integer, Long> sendTimes = new HashMap<>();
            long startTime = System.currentTimeMillis();
            System.out.println("Начало загрузки файла. Всего пакетов: " + packets.length);
            while(base < totalPackets) {
                int windowEnd = Math.min(totalPackets, base + WINDOW_SIZE);
                for (int seq = base; seq < windowEnd; seq++) {
                    int index = seq - offset;
                    if(index >= 0 && index < packets.length) {
                        if(!sendTimes.containsKey(seq) || (System.currentTimeMillis() - sendTimes.get(seq) > TIMEOUT_MS)) {
                            ByteBuffer buf = ByteBuffer.wrap(packets[index]);
                            channel.send(buf, serverAddress);
                            sendTimes.put(seq, System.currentTimeMillis());
                            System.out.println("Отправлен пакет " + seq + " (номер " + (seq - offset + 1) + " из " + packets.length + ")");
                        }
                    }
                }
                ByteBuffer ackBuf = ByteBuffer.allocate(HEADER_SIZE);
                SocketAddress ackAddr = channel.receive(ackBuf);
                if(ackAddr != null) {
                    ackBuf.flip();
                    byte type = ackBuf.get();
                    if(type == 1) {
                        int ackNum = ackBuf.getInt();
                        ackBuf.getInt(); // резерв
                        if(ackNum >= base) {
                            base = ackNum + 1;
                            Iterator<Integer> it = sendTimes.keySet().iterator();
                            while(it.hasNext()){
                                int seq = it.next();
                                if(seq <= ackNum) it.remove();
                            }
                            int uploadedPackets = base - offset;
                            double progress = (uploadedPackets * 100.0) / packets.length;
                            System.out.printf("Прогресс загрузки: %d/%d пакетов (%.2f%%)%n", uploadedPackets, packets.length, progress);
                        }
                    }
                }
            }
            long endTime = System.currentTimeMillis();
            long fileSize = file.length() - (long) offset * DATA_SIZE;
            double bitrate = (fileSize * 8.0) / ((endTime - startTime) / 1000.0);
            System.out.println("Загрузка завершена. Битрейт: " + bitrate + " бит/с");
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    // Скачивание файла (DOWNLOAD). Сохраняем в папку CLIENT_DIR с префиксом "downloaded_"
    private void downloadFile(String filename, int offset) {
        try {
            sendCommand("CMD:DOWNLOAD " + filename + " " + offset);
            String response = receiveCommand();
            if(!response.startsWith("READY_FOR_DOWNLOAD")) {
                System.out.println("Ошибка: " + response);
                return;
            }
            // Ответ: READY_FOR_DOWNLOAD filename offset totalPackets
            String[] parts = response.split(" ");
            if(parts.length < 4) {
                System.out.println("Некорректный ответ сервера");
                return;
            }
            int startSeq = offset;
            int totalPackets = Integer.parseInt(parts[3]);
            // Формируем путь для сохранения файла в папке CLIENT_DIR
            File outFile = new File(CLIENT_DIR, "downloaded_" + filename);
            RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
            int expectedSeq = startSeq;
            long startTime = System.currentTimeMillis();
            System.out.println("Начало скачивания файла. Всего пакетов: " + (totalPackets - offset));
            while(expectedSeq < totalPackets) {
                ByteBuffer buf = ByteBuffer.allocate(PACKET_SIZE);
                SocketAddress addr = channel.receive(buf);
                if(addr != null) {
                    buf.flip();
                    byte packetType = buf.get();
                    if(packetType == 0) {
                        int seqNum = buf.getInt();
                        buf.getInt(); // общее число пакетов (не используется здесь)
                        byte[] data = new byte[buf.remaining()];
                        buf.get(data);
                        if(seqNum == expectedSeq) {
                            raf.write(data);
                            expectedSeq++;
                            int downloadedPackets = expectedSeq - offset;
                            double progress = (downloadedPackets * 100.0) / (totalPackets - offset);
                            System.out.printf("Прогресс скачивания: %d/%d пакетов (%.2f%%)%n", downloadedPackets, totalPackets - offset, progress);
                        }
                        // Отправляем ACK
                        ByteBuffer ackBuf = ByteBuffer.allocate(HEADER_SIZE);
                        ackBuf.put((byte)1);
                        ackBuf.putInt(expectedSeq - 1);
                        ackBuf.putInt(0);
                        ackBuf.flip();
                        channel.send(ackBuf, serverAddress);
                    }
                }
            }
            long endTime = System.currentTimeMillis();
            raf.close();
            double bitrate = (outFile.length() * 8.0) / ((endTime - startTime) / 1000.0);
            System.out.println("Скачивание завершено. Файл сохранён: " + outFile.getAbsolutePath());
            System.out.println("Битрейт: " + bitrate + " бит/с");
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    // Разбивает файл на пакеты (аналогично серверу)
    private byte[][] createPackets(File file, int offset) throws IOException {
        long fileSize = file.length();
        long remainingBytes = fileSize - (long)offset * DATA_SIZE;
        int numPackets = (int)Math.ceil(remainingBytes / (double)DATA_SIZE);
        byte[][] packets = new byte[numPackets][];
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        raf.seek((long) offset * DATA_SIZE);
        for (int i = 0; i < numPackets; i++) {
            ByteBuffer dataBuf = ByteBuffer.allocate(DATA_SIZE);
            int bytesRead = raf.getChannel().read(dataBuf);
            if(bytesRead <= 0) break;
            dataBuf.flip();
            byte[] data = new byte[bytesRead];
            dataBuf.get(data);
            ByteBuffer packetBuf = ByteBuffer.allocate(HEADER_SIZE + bytesRead);
            packetBuf.put((byte)0);
            packetBuf.putInt(i + offset);
            packetBuf.putInt(numPackets + offset);
            packetBuf.put(data);
            packetBuf.flip();
            byte[] packetData = new byte[packetBuf.remaining()];
            packetBuf.get(packetData);
            packets[i] = packetData;
        }
        raf.close();
        return packets;
    }
}
