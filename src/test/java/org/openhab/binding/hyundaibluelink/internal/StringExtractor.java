package org.openhab.binding.hyundaibluelink.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class StringExtractor {
    public static void main(String[] args) {
        String filePath = "D:\\org.openhab.binding.hyundaibluelink\\APK\\temp_extract\\unzip_content\\assets\\.5673e389d113c08f10608abc6d5b5f18.dex";
        if (args.length > 0) {
            filePath = args[0];
        }

        System.out.println("Processing file: " + filePath);

        try {
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            // Simple extraction: filter for printable sequences that look like API paths
            // We look for patterns like /api/vX/... or /ccsp/...

            // Because DEX strings are length-prefixed MUTF-8, we can't just new
            // String(bytes).
            // But for simple ASCII URL paths, a simple linear scan for printable chars
            // works well enough for "strings" behavior.

            List<String> foundStrings = extractStrings(bytes, 5); // min length 5

            System.out.println("--- First 200 Strings (Sample) ---");
            int count = 0;
            for (String s : foundStrings) {
                // Filter out obvious noise (only alphanumeric/punctuation)
                if (s.matches("[\\w\\p{Punct}\\s]+")) {
                    System.out.println(s);
                    count++;
                    if (count >= 200)
                        break;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static List<String> extractStrings(byte[] data, int minLength) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (byte b : data) {
            char c = (char) (b & 0xFF);
            // Check for printable ASCII (approx)
            if (c >= 32 && c <= 126) {
                current.append(c);
            } else {
                if (current.length() >= minLength) {
                    result.add(current.toString());
                }
                current.setLength(0);
            }
        }
        // Add last one if exists
        if (current.length() >= minLength) {
            result.add(current.toString());
        }
        return result;
    }
}
