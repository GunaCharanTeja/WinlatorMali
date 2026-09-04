package com.winlator.cmod.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Downloader {

    public interface DownloadProgressListener {
        void onProgress(long currentBytes, long totalBytes, int percent, float speedMBs);
    }

    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, DownloadProgressListener listener) {
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return false;
            }

            long totalBytes = connection.getContentLengthLong();
            input = connection.getInputStream();
            output = new FileOutputStream(file.getAbsolutePath());

            byte[] data = new byte[65536];
            int count;
            long currentBytes = 0;
            long startTime = System.currentTimeMillis();
            long lastUpdateTime = 0;

            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
                currentBytes += count;

                long now = System.currentTimeMillis();
                if (listener != null && (now - lastUpdateTime > 100 || currentBytes == totalBytes)) {
                    lastUpdateTime = now;
                    int percent = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : -1;
                    float elapsedSec = (now - startTime) / 1000f;
                    float speedMBs = elapsedSec > 0 ? (currentBytes / (1024f * 1024f)) / elapsedSec : 0f;
                    listener.onProgress(currentBytes, totalBytes, percent, speedMBs);
                }
            }

            output.flush();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (output != null) output.close();
                if (input != null) input.close();
                if (connection != null) connection.disconnect();
            } catch (Exception ignored) {}
        }
    }

    public static String downloadString(String address) {
        HttpURLConnection connection = null;
        InputStream input = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            input = connection.getInputStream();
            reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
                if (input != null) input.close();
                if (connection != null) connection.disconnect();
            } catch (Exception ignored) {}
        }
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024f * 1024f));
        return String.format("%.2f GB", bytes / (1024f * 1024f * 1024f));
    }
}