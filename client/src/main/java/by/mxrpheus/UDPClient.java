package by.mxrpheus;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class UDPClient {
    private static final int DATA_BUFFER_SIZE = 1400;
    private static final int WINDOW_SIZE = 5;
    private static final int TIMEOUT = 1000;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Введите IP сервера: ");
        String serverIP = scanner.nextLine();
        System.out.print("Введите порт сервера: ");
        int serverPort = Integer.parseInt(scanner.nextLine());

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT);
            InetAddress serverAddress = InetAddress.getByName(serverIP);

            // Основной цикл команд
            while (true) {
                System.out.println("\nВведите команду (ECHO <текст>, TIME, UPLOAD <путь к файлу>, DOWNLOAD <имя файла>, EXIT):");
                String commandLine = scanner.nextLine().trim();

                // Завершение работы клиента
                if (commandLine.equalsIgnoreCase("EXIT") ||
                        commandLine.equalsIgnoreCase("CLOSE") ||
                        commandLine.equalsIgnoreCase("QUIT"))
                {
                    byte[] data = commandLine.getBytes();
                    DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
                    socket.send(packet);
                    System.out.println("Завершение работы клиента.");
                    break;
                }

                // Команда UPLOAD – загрузка файла на сервер
                if (commandLine.toUpperCase().startsWith("UPLOAD")) {
                    String[] parts = commandLine.split("\\s+", 2);
                    if (parts.length < 2) {
                        System.out.println("Неверный формат команды UPLOAD. Используйте: UPLOAD <путь к файлу>");
                        continue;
                    }
                    String filePath = parts[1];
                    File file = new File(filePath);
                    if (!file.exists()) {
                        System.out.println("Файл не найден: " + filePath);
                        continue;
                    }
                    // Отправляем команду "UPLOAD <имя файла>"
                    String uploadCmd = "UPLOAD " + file.getName();
                    byte[] cmdData = uploadCmd.getBytes();
                    DatagramPacket cmdPacket = new DatagramPacket(cmdData, cmdData.length, serverAddress, serverPort);
                    socket.send(cmdPacket);
                    System.out.println("Отправлена команда: " + uploadCmd);

                    // Ожидаем ответ от сервера: если длина не равна 8, то получено сообщение об ошибке
                    byte[] posBuffer = new byte[1024];
                    DatagramPacket posPacket = new DatagramPacket(posBuffer, posBuffer.length);
                    socket.receive(posPacket);
                    if (posPacket.getLength() != 8) {
                        String errorMsg = new String(posPacket.getData(), 0, posPacket.getLength()).trim();
                        System.out.println("Ошибка при загрузке файла: " + errorMsg);
                        continue;
                    }
                    long startPosition = ByteBuffer.wrap(posBuffer, 0, 8).getLong();
                    System.out.println("Сервер сообщает, что файл уже имеет " + startPosition + " байт (начинаем с этого места)");

                    FileInputStream fis = new FileInputStream(file);
                    fis.skip(startPosition);

                    // Читаем файл и формируем пакеты (каждый пакет: 4 байта seq + 4 байта длины данных + данные)
                    List<byte[]> packets = new ArrayList<>();
                    int seqNumber = 0;
                    byte[] fileBuffer = new byte[DATA_BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = fis.read(fileBuffer)) != -1) {
                        ByteBuffer packetBuffer = ByteBuffer.allocate(4 + 4 + bytesRead);
                        packetBuffer.putInt(seqNumber);
                        packetBuffer.putInt(bytesRead);
                        packetBuffer.put(fileBuffer, 0, bytesRead);
                        packets.add(packetBuffer.array());
                        seqNumber++;
                    }
                    fis.close();
                    int totalPackets = packets.size();
                    System.out.println("Всего пакетов для отправки: " + totalPackets);

                    boolean[] acked = new boolean[totalPackets];
                    int base = 0;
                    long startTime = System.currentTimeMillis();

                    // Отправка пакетов с использованием скользящего окна
                    while (base < totalPackets) {
                        int windowEnd = Math.min(base + WINDOW_SIZE, totalPackets);
                        for (int i = base; i < windowEnd; i++) {
                            if (!acked[i]) {
                                DatagramPacket packet = new DatagramPacket(packets.get(i), packets.get(i).length, serverAddress, serverPort);
                                socket.send(packet);
                                System.out.println("Отправлен пакет seq=" + i);
                            }
                        }
                        int acksReceived = 0;
                        while (acksReceived < (windowEnd - base)) {
                            try {
                                byte[] ackBuffer = new byte[4];
                                DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);
                                socket.receive(ackPacket);
                                int ackSeq = ByteBuffer.wrap(ackBuffer).getInt();
                                System.out.println("Получен ACK для seq=" + ackSeq);
                                if (ackSeq >= base && ackSeq < windowEnd && !acked[ackSeq]) {
                                    acked[ackSeq] = true;
                                    acksReceived++;
                                }
                            } catch (SocketTimeoutException e) {
                                System.out.println("Таймаут ожидания ACK, повторная отправка непринятых пакетов окна");
                                break;
                            }
                        }
                        while (base < totalPackets && acked[base]) {
                            base++;
                        }
                    }
                    // Отправляем FIN-пакет для завершения передачи (seq = -1)
                    ByteBuffer finBuffer = ByteBuffer.allocate(4);
                    finBuffer.putInt(-1);
                    DatagramPacket finPacket = new DatagramPacket(finBuffer.array(), finBuffer.array().length, serverAddress, serverPort);
                    socket.send(finPacket);
                    long endTime = System.currentTimeMillis();
                    double duration = (endTime - startTime) / 1000.0;
                    double bitrate = (file.length() * 8) / (duration * 1024 * 1024.0);
                    System.out.printf("Передача файла завершена за %.2f секунд. Битрейт: %.2f Мбит/с%n", duration, bitrate);
                    continue;
                }
                // Команда DOWNLOAD – скачивание файла с сервера с дозагрузкой
                else if (commandLine.toUpperCase().startsWith("DOWNLOAD")) {
                    String[] parts = commandLine.split("\\s+", 2);
                    if (parts.length < 2) {
                        System.out.println("Неверный формат команды DOWNLOAD. Используйте: DOWNLOAD <имя файла>");
                        continue;
                    }
                    String fileName = parts[1].trim();
                    String downloadCmd = "DOWNLOAD " + fileName;
                    byte[] cmdData = downloadCmd.getBytes();
                    DatagramPacket cmdPacket = new DatagramPacket(cmdData, cmdData.length, serverAddress, serverPort);

                    // Отправляем команду один раз
                    socket.send(cmdPacket);
                    System.out.println("Отправлена команда: " + downloadCmd);

                    // Увеличиваем таймаут для ожидания первого ответа (размера файла)
                    socket.setSoTimeout(3000);
                    byte[] respBuffer = new byte[1024];
                    DatagramPacket respPacket = new DatagramPacket(respBuffer, respBuffer.length);
                    try {
                        socket.receive(respPacket);
                    } catch (SocketTimeoutException e) {
                        System.out.println("Не удалось получить ответ от сервера о размере файла.");
                        socket.setSoTimeout(TIMEOUT); // возвращаем исходный таймаут
                        continue;
                    }

                    // Если полученный пакет не равен 8 байтам, это сообщение об ошибке
                    if (respPacket.getLength() != 8) {
                        String errorMsg = new String(respPacket.getData(), 0, respPacket.getLength()).trim();
                        System.out.println("Ошибка при скачивании файла: " + errorMsg);
                        socket.setSoTimeout(TIMEOUT);
                        continue;
                    }

                    long totalFileSize = ByteBuffer.wrap(respBuffer, 0, 8).getLong();
                    System.out.println("Размер файла для скачивания: " + totalFileSize + " байт.");

                    // Если файл уже существует, определяем размер локального файла
                    File file = new File("client/files/" + fileName);
                    long localSize = 0;
                    if (file.exists()) {
                        localSize = file.length();
                        if (localSize >= totalFileSize) {
                            System.out.println("Файл уже полностью загружен.");
                            socket.setSoTimeout(TIMEOUT);
                            continue;
                        }
                        System.out.println("Найден частично загруженный файл. Локальный размер: " + localSize +
                                " байт. Будет скачано: " + (totalFileSize - localSize) + " байт.");
                    } else {
                        file.getParentFile().mkdirs();
                        file.createNewFile();
                    }

                    // Отправляем серверу позицию, с которой надо начать загрузку (8 байт)
                    ByteBuffer resumeBuffer = ByteBuffer.allocate(8);
                    resumeBuffer.putLong(localSize);
                    DatagramPacket resumePacket = new DatagramPacket(resumeBuffer.array(), resumeBuffer.array().length, serverAddress, serverPort);
                    socket.send(resumePacket);

                    // Возвращаем исходный таймаут для приема данных
                    socket.setSoTimeout(5000);

                    // Открываем поток для дозагрузки (append)
                    FileOutputStream fos = new FileOutputStream(file, true);
                    long bytesReceived = localSize;
                    long startTimeDownload = System.currentTimeMillis();

                    int timeoutCount = 0; // счетчик таймаутов при приеме пакетов
                    while (true) {
                        try {
                            byte[] packetBuffer = new byte[1500];
                            DatagramPacket filePacket = new DatagramPacket(packetBuffer, packetBuffer.length);
                            socket.receive(filePacket);
                            // Сброс счетчика, если пакет получен
                            timeoutCount = 0;
                            ByteBuffer bb = ByteBuffer.wrap(filePacket.getData(), 0, filePacket.getLength());
                            int seq = bb.getInt();
                            if (seq == -1) {
                                System.out.println("Получен FIN-пакет. Загрузка завершена.");
                                break;
                            }
                            int dataLength = bb.getInt();
                            byte[] fileData = new byte[dataLength];
                            bb.get(fileData, 0, dataLength);
                            fos.write(fileData);
                            bytesReceived += dataLength;
                            // Отправляем ACK для полученного пакета
                            ByteBuffer ackBuf = ByteBuffer.allocate(4);
                            ackBuf.putInt(seq);
                            DatagramPacket ackPacket = new DatagramPacket(ackBuf.array(), ackBuf.array().length, serverAddress, serverPort);
                            socket.send(ackPacket);
                            System.out.println("Получен пакет seq=" + seq + ", отправлен ACK.");
                        } catch (SocketTimeoutException e) {
                            timeoutCount++;
                            System.out.println("Таймаут ожидания пакета DOWNLOAD (попытка " + timeoutCount + ").");
                            if (timeoutCount >= 3) {
                                System.out.println("Превышено число таймаутов. Считаем, что передача завершена.");
                                break;
                            }
                        }
                    }
                    fos.close();
                    long endTimeDownload = System.currentTimeMillis();
                    double durationDownload = (endTimeDownload - startTimeDownload) / 1000.0;
                    double bitrateDownload = ((bytesReceived - localSize) * 8) / (durationDownload * 1024 * 1024.0);
                    System.out.printf("Скачивание завершено за %.2f секунд. Битрейт: %.2f Мбит/с%n", durationDownload, bitrateDownload);
                    // Возвращаем исходный таймаут
                    socket.setSoTimeout(TIMEOUT);
                    continue;
                }

                // Если команда не является UPLOAD или DOWNLOAD – обрабатываем как текстовую (например, ECHO, TIME)
                else {
                    byte[] data = commandLine.getBytes();
                    DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
                    socket.send(packet);
                    byte[] respBuffer2 = new byte[1024];
                    DatagramPacket respPacket2 = new DatagramPacket(respBuffer2, respBuffer2.length);
                    try {
                        socket.receive(respPacket2);
                        String response = new String(respPacket2.getData(), 0, respPacket2.getLength()).trim();
                        System.out.println("Ответ от сервера: " + response);
                    } catch (SocketTimeoutException e) {
                        System.out.println("Таймаут ожидания ответа от сервера.");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка UDP-клиента: " + e.getMessage());
        }
    }
}