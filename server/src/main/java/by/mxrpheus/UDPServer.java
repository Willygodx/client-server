package by.mxrpheus;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class UDPServer {
    private static final int PORT = 9876;
    private static final int BUFFER_SIZE = 1500;
    private static final int DATA_BUFFER_SIZE = 1400;
    private static final int WINDOW_SIZE = 5;

    private static class UploadSession {
        InetAddress clientAddress;
        int clientPort;
        String fileName;
        FileOutputStream fos;

        UploadSession(InetAddress clientAddress, int clientPort, String fileName, FileOutputStream fos) {
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.fileName = fileName;
            this.fos = fos;
        }
    }
    // Поддерживается только одна активная сессия загрузки для каждого клиента
    private static UploadSession currentUploadSession = null;

    public static void main(String[] args) {
        try (DatagramSocket socket = new DatagramSocket(PORT)) {
            System.out.println("UDP-сервер запущен на порту " + PORT);
            byte[] buffer = new byte[BUFFER_SIZE];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                InetAddress clientAddr = packet.getAddress();
                int clientPort = packet.getPort();
                String message = new String(packet.getData(), 0, packet.getLength()).trim();

                // Обработка текстовых команд
                if (message.startsWith("ECHO")) {
                    String echoMsg = message.length() > 5 ? message.substring(5) : "";
                    DatagramPacket echoPacket = new DatagramPacket(echoMsg.getBytes(), echoMsg.getBytes().length, clientAddr, clientPort);
                    socket.send(echoPacket);
                    System.out.println("Выполнена команда ECHO для " + clientAddr + ":" + clientPort);
                    continue;
                } else if (message.equalsIgnoreCase("TIME")) {
                    String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    String resp = "Server time: " + timeStr;
                    DatagramPacket timePacket = new DatagramPacket(resp.getBytes(), resp.getBytes().length, clientAddr, clientPort);
                    socket.send(timePacket);
                    System.out.println("Отправлено время клиенту " + clientAddr + ":" + clientPort);
                    continue;
                } else if (message.equalsIgnoreCase("CLOSE") ||
                        message.equalsIgnoreCase("EXIT") ||
                        message.equalsIgnoreCase("QUIT")) {
                    String resp = "Соединение закрыто сервером.";
                    DatagramPacket closePacket = new DatagramPacket(resp.getBytes(), resp.getBytes().length, clientAddr, clientPort);
                    socket.send(closePacket);
                    System.out.println("Получена команда CLOSE от " + clientAddr + ":" + clientPort);
                    continue;
                }
                // Команда UPLOAD – загрузка файла на сервер (с поддержкой resume)
                else if (message.startsWith("UPLOAD ")) {
                    String fileName = message.substring(7).trim();
                    File file = new File("server/files/" + fileName);
                    file.getParentFile().mkdirs();

                    // Если уже существует активная сессия для данного клиента, завершаем её
                    if (currentUploadSession != null &&
                            clientAddr.equals(currentUploadSession.clientAddress) &&
                            clientPort == currentUploadSession.clientPort) {
                        try {
                            currentUploadSession.fos.close();
                        } catch (IOException e) {
                            System.err.println("Ошибка закрытия предыдущей сессии: " + e.getMessage());
                        }
                        currentUploadSession = null;
                    }

                    // Определяем текущую длину файла для дозагрузки (resume offset)
                    long currentLength = file.exists() ? file.length() : 0;

                    // Открываем файл в режиме append (дозагрузка)
                    FileOutputStream fos = new FileOutputStream(file, true);
                    currentUploadSession = new UploadSession(clientAddr, clientPort, fileName, fos);
                    System.out.println("Начата загрузка файла " + file.getAbsolutePath() +
                            " от " + clientAddr + ":" + clientPort +
                            ". Текущий размер: " + currentLength + " байт");

                    // Отправляем клиенту текущее количество байт (resume offset) – 8 байт
                    ByteBuffer posBuffer = ByteBuffer.allocate(8);
                    posBuffer.putLong(currentLength);
                    DatagramPacket posPacket = new DatagramPacket(posBuffer.array(), posBuffer.array().length, clientAddr, clientPort);
                    socket.send(posPacket);
                    continue;
                }

                // Команда DOWNLOAD – скачивание файла с сервера с поддержкой дозагрузки
                else if (message.startsWith("DOWNLOAD ")) {
                    String fileName = message.substring(9).trim();
                    File file = new File("server/files/" + fileName);
                    if (!file.exists()) {
                        String errMsg = "ERROR: Файл " + fileName + " не найден на сервере.";
                        DatagramPacket errPacket = new DatagramPacket(errMsg.getBytes(), errMsg.getBytes().length, clientAddr, clientPort);
                        socket.send(errPacket);
                        System.out.println("Запрошен несуществующий файл " + fileName + " от " + clientAddr + ":" + clientPort);
                        continue;
                    }
                    // Отправляем размер файла (8 байт) клиенту
                    ByteBuffer sizeBuffer = ByteBuffer.allocate(8);
                    sizeBuffer.putLong(file.length());
                    DatagramPacket sizePacket = new DatagramPacket(sizeBuffer.array(), sizeBuffer.array().length, clientAddr, clientPort);
                    socket.send(sizePacket);
                    System.out.println("Отправлен размер файла " + fileName + " (" + file.length() + " байт) клиенту " + clientAddr + ":" + clientPort);

                    // Ожидаем от клиента позицию (resume offset, 8 байт)
                    byte[] resumeBuf = new byte[8];
                    DatagramPacket resumePacket = new DatagramPacket(resumeBuf, resumeBuf.length);
                    try {
                        socket.receive(resumePacket);
                    } catch (SocketTimeoutException e) {
                        System.out.println("Таймаут ожидания resume offset от клиента " + clientAddr + ":" + clientPort);
                        continue;
                    }
                    long resumeOffset = ByteBuffer.wrap(resumeBuf).getLong();
                    System.out.println("Получен resume offset: " + resumeOffset + " от " + clientAddr + ":" + clientPort);

                    // Открываем файл, пропускаем resumeOffset байт и готовим передачу оставшихся данных
                    FileInputStream fis = new FileInputStream(file);
                    fis.skip(resumeOffset);
                    byte[] fileBuffer = new byte[DATA_BUFFER_SIZE];
                    List<byte[]> filePackets = new ArrayList<>();
                    int seq = 0;
                    int readBytes;
                    while ((readBytes = fis.read(fileBuffer)) != -1) {
                        ByteBuffer packetBuffer = ByteBuffer.allocate(4 + 4 + readBytes);
                        packetBuffer.putInt(seq);
                        packetBuffer.putInt(readBytes);
                        packetBuffer.put(fileBuffer, 0, readBytes);
                        filePackets.add(packetBuffer.array());
                        seq++;
                    }
                    fis.close();
                    int totalPackets = filePackets.size();

                    boolean[] acked = new boolean[totalPackets];
                    int base = 0;
                    long startTime = System.currentTimeMillis();
                    // Отправка пакетов с использованием скользящего окна (код отправки без изменений)
                    while (base < totalPackets) {
                        int windowEnd = Math.min(base + WINDOW_SIZE, totalPackets);
                        for (int i = base; i < windowEnd; i++) {
                            if (!acked[i]) {
                                DatagramPacket dataPacket = new DatagramPacket(filePackets.get(i), filePackets.get(i).length, clientAddr, clientPort);
                                socket.send(dataPacket);
                                System.out.println("Отправлен пакет seq=" + i + " для DOWNLOAD");
                            }
                        }
                        int acksReceived = 0;
                        while (acksReceived < (windowEnd - base)) {
                            try {
                                byte[] ackBuf = new byte[4];
                                DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                                socket.receive(ackPacket);
                                int ackSeq = ByteBuffer.wrap(ackBuf).getInt();
                                System.out.println("Получен ACK для seq=" + ackSeq + " в DOWNLOAD");
                                if (ackSeq >= base && ackSeq < windowEnd && !acked[ackSeq]) {
                                    acked[ackSeq] = true;
                                    acksReceived++;
                                }
                            } catch (SocketTimeoutException e) {
                                System.out.println("Таймаут ожидания ACK в DOWNLOAD, повторная отправка непринятых пакетов");
                                break;
                            }
                        }
                        while (base < totalPackets && acked[base]) {
                            base++;
                        }
                    }
                    // Отправляем FIN-пакет (seq = -1) для завершения передачи
                    ByteBuffer finBuffer = ByteBuffer.allocate(4);
                    finBuffer.putInt(-1);
                    DatagramPacket finPacket = new DatagramPacket(finBuffer.array(), finBuffer.array().length, clientAddr, clientPort);
                    socket.send(finPacket);
                    long endTime = System.currentTimeMillis();
                    double duration = (endTime - startTime) / 1000.0;
                    double bitrate = ((file.length() - resumeOffset) * 8) / (duration * 1024 * 1024.0);
                    System.out.printf("Отправка файла завершена за %.2f секунд. Битрейт: %.2f Мбит/с%n", duration, bitrate);
                    continue;
                }

                // Если сообщение не является командой, а данные для активной сессии UPLOAD
                else {
                    if (currentUploadSession != null &&
                            clientAddr.equals(currentUploadSession.clientAddress) &&
                            clientPort == currentUploadSession.clientPort &&
                            packet.getLength() >= 8)
                    {
                        ByteBuffer bb = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                        int seq = bb.getInt();
                        if (seq == -1) {
                            System.out.println("Получен FIN-пакет для UPLOAD от " + clientAddr + ":" + clientPort);
                            currentUploadSession.fos.close();
                            currentUploadSession = null;
                            continue;
                        }
                        int dataLength = bb.getInt();
                        byte[] data = new byte[dataLength];
                        bb.get(data, 0, dataLength);
                        try {
                            currentUploadSession.fos.write(data);
                            System.out.println("Записан пакет seq=" + seq + " (" + dataLength + " байт) от " + clientAddr + ":" + clientPort);
                        } catch (IOException e) {
                            System.err.println("Ошибка записи файла: " + e.getMessage());
                        }
                        // Отправляем ACK
                        ByteBuffer ackBuffer = ByteBuffer.allocate(4);
                        ackBuffer.putInt(seq);
                        DatagramPacket ackPacket = new DatagramPacket(ackBuffer.array(), ackBuffer.array().length, clientAddr, clientPort);
                        socket.send(ackPacket);
                    }
                    else {
                        String resp = "Нераспознанная команда или отсутствие активной сессии загрузки.";
                        DatagramPacket respPacket = new DatagramPacket(resp.getBytes(), resp.getBytes().length, clientAddr, clientPort);
                        socket.send(respPacket);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка UDP-сервера: " + e.getMessage());
        }
    }
}