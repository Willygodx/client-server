package by.mxrpheus;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class Server {
    private static final int PORT = 9001;
    private static final int PACKET_SIZE = 1024;
    private static final int HEADER_SIZE = 9; // 1 байт: тип, 4 байта: seq, 4 байта: totalPackets
    private static final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE;
    private static final int WINDOW_SIZE = 5;
    private static final long TIMEOUT_MS = 500; // таймаут повторной отправки

    // Папка для хранения файлов на сервере
    private static final String SERVER_DIR = "server_files";

    private DatagramChannel channel;
    private Selector selector;

    // Сессии загрузки (UPLOAD) от клиента к серверу
    private Map<String, UploadSession> uploadSessions = new HashMap<>();
    // Сессии скачивания (DOWNLOAD) от сервера к клиенту
    private Map<String, DownloadSession> downloadSessions = new HashMap<>();

    public static void main(String[] args) {
        new Server().start();
    }

    public void start() {
        try {
            // Создаем папку для хранения файлов на сервере, если она не существует
            File serverDir = new File(SERVER_DIR);
            if (!serverDir.exists()) {
                serverDir.mkdirs();
            }
            channel = DatagramChannel.open();
            channel.socket().bind(new InetSocketAddress(PORT));
            channel.configureBlocking(false);
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);
            System.out.println("UDP-сервер запущен на порту " + PORT);
            System.out.println("Файлы сервера будут храниться в папке: " + serverDir.getAbsolutePath());

            while (true) {
                selector.select(50);
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()){
                    SelectionKey key = it.next();
                    it.remove();
                    if (key.isReadable()){
                        ByteBuffer buf = ByteBuffer.allocate(PACKET_SIZE);
                        SocketAddress clientAddr = channel.receive(buf);
                        if (clientAddr == null) continue;
                        buf.flip();
                        processPacket(buf, clientAddr);
                    }
                }
                updateDownloadSessions();
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    // Разбираем входящий пакет: если начинается с "CMD:" – это управляющая команда,
    // иначе – бинарный пакет (данные или ACK).
    private void processPacket(ByteBuffer buf, SocketAddress clientAddr) throws IOException {
        if(buf.remaining() < 4) return; // слишком короткий пакет
        byte[] peek = new byte[4];
        buf.get(peek);
        buf.rewind();
        String prefix = new String(peek);
        if(prefix.equals("CMD:")) {
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            String cmd = new String(bytes).trim();
            processCommand(cmd.substring(4), clientAddr); // убираем "CMD:"
        } else {
            byte packetType = buf.get();
            if(packetType == 0) { // data-пакет (UPLOAD)
                int seqNum = buf.getInt();
                int totalPackets = buf.getInt();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                processFileDataPacket(clientAddr, seqNum, totalPackets, data);
            } else if(packetType == 1) { // ACK-пакет (при DOWNLOAD)
                int ackNum = buf.getInt();
                buf.getInt(); // резервное поле
                processAckPacket(clientAddr, ackNum);
            }
        }
    }

    // Обработка управляющих команд
    private void processCommand(String cmd, SocketAddress clientAddr) throws IOException {
        System.out.println("Получена команда от " + clientAddr + ": " + cmd);
        String[] parts = cmd.split(" ");
        String command = parts[0].toUpperCase();
        switch(command) {
            case "ECHO":
                String message = cmd.substring(5);
                sendCommand("CMD:ECHO " + message, clientAddr);
                break;
            case "TIME":
                String time = new Date().toString();
                sendCommand("CMD:TIME " + time, clientAddr);
                break;
            case "CLOSE":
                sendCommand("CMD:CLOSE", clientAddr);
                break;
            case "UPLOAD":
                // Формат: UPLOAD filename [offset]
                if(parts.length < 2) {
                    sendCommand("CMD:ERROR Missing filename for UPLOAD", clientAddr);
                    return;
                }
                String upFilename = parts[1];
                int upOffset = 0;
                if(parts.length >= 3) {
                    try {
                        upOffset = Integer.parseInt(parts[2]);
                    } catch(NumberFormatException e) {
                        upOffset = 0;
                    }
                }
                // Файл будет сохранен в папке SERVER_DIR
                File upFile = new File(SERVER_DIR, upFilename);
                RandomAccessFile rafUp = new RandomAccessFile(upFile, "rw");
                rafUp.seek((long) upOffset * DATA_SIZE);
                FileChannel fcUp = rafUp.getChannel();
                UploadSession upSession = new UploadSession(clientAddr, upFilename, upOffset, fcUp);
                uploadSessions.put(clientAddr.toString() + "_" + upFilename, upSession);
                sendCommand("CMD:READY_FOR_UPLOAD " + upFilename + " " + upOffset, clientAddr);
                break;
            case "DOWNLOAD":
                // Формат: DOWNLOAD filename [offset]
                if(parts.length < 2) {
                    sendCommand("CMD:ERROR Missing filename for DOWNLOAD", clientAddr);
                    return;
                }
                String downFilename = parts[1];
                int downOffset = 0;
                if(parts.length >= 3) {
                    try {
                        downOffset = Integer.parseInt(parts[2]);
                    } catch(NumberFormatException e) {
                        downOffset = 0;
                    }
                }
                // Файл для скачивания находится в папке SERVER_DIR
                File file = new File(SERVER_DIR, downFilename);
                if(!file.exists()){
                    sendCommand("CMD:ERROR File not found", clientAddr);
                    return;
                }
                // Разбиваем файл на пакеты начиная с заданного offset (номер пакета)
                byte[][] packets = createPackets(file, downOffset);
                String downKey = clientAddr.toString() + "_" + downFilename;
                DownloadSession downSession = new DownloadSession(clientAddr, downFilename, packets, downOffset);
                downloadSessions.put(downKey, downSession);
                // Ответ: filename, offset и общее число пакетов (последний seq+1)
                sendCommand("CMD:READY_FOR_DOWNLOAD " + downFilename + " " + downOffset + " " + (packets.length + downOffset), clientAddr);
                break;
            default:
                sendCommand("CMD:ERROR Unknown command", clientAddr);
        }
    }

    // Отправка управляющего сообщения клиенту
    private void sendCommand(String cmd, SocketAddress clientAddr) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(cmd.getBytes());
        channel.send(buf, clientAddr);
    }

    // Обработка data-пакета при UPLOAD
    private void processFileDataPacket(SocketAddress clientAddr, int seqNum, int totalPackets, byte[] data) throws IOException {
        UploadSession session = null;
        for(UploadSession s : uploadSessions.values()){
            if(s.client.equals(clientAddr)) {
                session = s;
                break;
            }
        }
        if(session == null) return;
        if(seqNum == session.expectedSeq) {
            session.fc.write(ByteBuffer.wrap(data));
            session.expectedSeq++;
            // Если все пакеты получены – закрываем сессию
            if(session.expectedSeq >= totalPackets) {
                session.fc.close();
                String key = clientAddr.toString() + "_" + session.filename;
                uploadSessions.remove(key);
                System.out.println("Загрузка файла " + session.filename + " завершена от " + clientAddr);
            }
        }
        // Отправляем ACK с последним корректно полученным номером
        sendAck(session.client, session.expectedSeq - 1);
    }

    // Отправка ACK-пакета
    private void sendAck(SocketAddress clientAddr, int ackNum) throws IOException {
        ByteBuffer ackBuf = ByteBuffer.allocate(HEADER_SIZE);
        ackBuf.put((byte)1); // тип ACK
        ackBuf.putInt(ackNum);
        ackBuf.putInt(0);
        ackBuf.flip();
        channel.send(ackBuf, clientAddr);
    }

    // Обработка полученного ACK-пакета (при DOWNLOAD)
    private void processAckPacket(SocketAddress clientAddr, int ackNum) {
        for(DownloadSession session : downloadSessions.values()){
            if(session.client.equals(clientAddr)) {
                session.handleAck(ackNum);
                break;
            }
        }
    }

    // Разбиение файла на пакеты (начиная с указанного offset)
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
            packetBuf.put((byte)0); // data-пакет
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

    // Безопасное удаление завершённых сессий скачивания.
    private void updateDownloadSessions() throws IOException {
        Iterator<Map.Entry<String, DownloadSession>> it = downloadSessions.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, DownloadSession> entry = it.next();
            DownloadSession session = entry.getValue();
            session.retransmitIfNeeded();
            if(session.isFinished()){
                it.remove();
            }
        }
    }

    // Сессия для UPLOAD
    class UploadSession {
        SocketAddress client;
        String filename;
        int expectedSeq;
        FileChannel fc;
        public UploadSession(SocketAddress client, String filename, int expectedSeq, FileChannel fc) {
            this.client = client;
            this.filename = filename;
            this.expectedSeq = expectedSeq;
            this.fc = fc;
        }
    }

    // Сессия для DOWNLOAD с реализацией скользящего окна
    class DownloadSession {
        SocketAddress client;
        String filename;
        byte[][] packets;
        int startSeq; // начальный номер пакета (offset)
        int base;     // нижняя граница окна (неподтверждённый номер)
        Map<Integer, Long> sendTimes = new HashMap<>();
        private boolean finished = false;
        public DownloadSession(SocketAddress client, String filename, byte[][] packets, int startSeq) {
            this.client = client;
            this.filename = filename;
            this.packets = packets;
            this.startSeq = startSeq;
            this.base = startSeq;
        }
        // При получении ACK сдвигаем окно
        public void handleAck(int ackNum) {
            if(ackNum >= base) {
                base = ackNum + 1;
                Iterator<Integer> it = sendTimes.keySet().iterator();
                while(it.hasNext()){
                    int seq = it.next();
                    if(seq <= ackNum) it.remove();
                }
            }
        }
        // Отправка пакетов в пределах окна и повторная отправка при таймауте
        public void retransmitIfNeeded() throws IOException {
            int windowEnd = Math.min(startSeq + packets.length, base + WINDOW_SIZE);
            for(int seq = base; seq < windowEnd; seq++){
                if(!sendTimes.containsKey(seq) || (System.currentTimeMillis() - sendTimes.get(seq) > TIMEOUT_MS)) {
                    int index = seq - startSeq;
                    if(index >= 0 && index < packets.length) {
                        ByteBuffer buf = ByteBuffer.wrap(packets[index]);
                        channel.send(buf, client);
                        sendTimes.put(seq, System.currentTimeMillis());
                    }
                }
            }
            if(base >= startSeq + packets.length && !finished) {
                System.out.println("Скачивание файла " + filename + " завершено для " + client);
                finished = true;
            }
        }
        public boolean isFinished() {
            return finished;
        }
    }
}
