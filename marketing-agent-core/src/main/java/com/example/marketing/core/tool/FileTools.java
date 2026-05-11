package com.example.marketing.core.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;

import com.example.marketing.core.model.ToolResult;

@Service
public class FileTools {
    private static final Pattern CELL_PATTERN = Pattern.compile("<c[^>]*r=\"([A-Z]+)(\\d+)\"[^>]*?(?:t=\"([^\"]+)\")?[^>]*>(.*?)</c>",
            Pattern.DOTALL);
    private static final Pattern VALUE_PATTERN = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
    private static final Pattern INLINE_PATTERN = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);

    public ToolResult summarizeExcelContent(String pathText) {
        Path path = Path.of(pathText == null ? "" : pathText);
        if (!Files.exists(path)) {
            return ToolResult.failed("summarize_excel_content", "FILE_NOT_FOUND",
                    "没有找到这个文件，请确认本地路径是否正确。", path.toString(), false);
        }
        try {
            String lower = path.getFileName().toString().toLowerCase();
            if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
                return summarizeDelimited(path);
            }
            if (lower.endsWith(".xlsx")) {
                return summarizeXlsx(path);
            }
            return ToolResult.failed("summarize_excel_content", "UNSUPPORTED_FILE_TYPE",
                    "当前只支持 .xlsx、.csv 或 .txt 文件。", path.toString(), false);
        }
        catch (IOException ex) {
            return ToolResult.failed("summarize_excel_content", "READ_FAILED",
                    "读取文件失败，文件可能被占用或格式异常。", ex.getMessage(), true);
        }
    }

    private ToolResult summarizeDelimited(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> headers = lines.isEmpty() ? List.of() : splitLine(lines.get(0));
        List<List<String>> samples = lines.stream().skip(1).limit(5).map(this::splitLine).toList();
        return ToolResult.ok("summarize_excel_content", Map.of(
                "file_path", path.toString(),
                "sheet_count", 1,
                "row_count", Math.max(0, lines.size() - 1),
                "columns", headers,
                "sample_rows", samples,
                "csv_preview", String.join("\n", lines.stream().limit(8).toList())
        ));
    }

    private List<String> splitLine(String line) {
        String[] parts = line.split(",|\\t");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            values.add(part.trim());
        }
        return values;
    }

    private ToolResult summarizeXlsx(Path path) throws IOException {
        Map<String, String> entries = unzipSelectedEntries(path);
        List<String> sharedStrings = parseSharedStrings(entries.getOrDefault("xl/sharedStrings.xml", ""));
        String sheetXml = entries.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("xl/worksheets/sheet") && entry.getKey().endsWith(".xml"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        List<List<String>> rows = parseRows(sheetXml, sharedStrings);
        List<String> headers = rows.isEmpty() ? List.of() : rows.get(0);
        List<List<String>> samples = rows.stream().skip(1).limit(5).toList();
        return ToolResult.ok("summarize_excel_content", Map.of(
                "file_path", path.toString(),
                "sheet_count", entries.keySet().stream().filter(name -> name.startsWith("xl/worksheets/sheet")).count(),
                "row_count", Math.max(0, rows.size() - 1),
                "columns", headers,
                "sample_rows", samples,
                "csv_preview", toCsvPreview(rows)
        ));
    }

    private Map<String, String> unzipSelectedEntries(Path path) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/sharedStrings.xml".equals(name)
                        || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))) {
                    entries.put(name, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    private List<String> parseSharedStrings(String xml) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(xml);
        while (matcher.find()) {
            Matcher textMatcher = INLINE_PATTERN.matcher(matcher.group(1));
            StringBuilder value = new StringBuilder();
            while (textMatcher.find()) {
                value.append(unescapeXml(textMatcher.group(1)));
            }
            values.add(value.toString());
        }
        return values;
    }

    private List<List<String>> parseRows(String xml, List<String> sharedStrings) {
        Map<Integer, Map<Integer, String>> rows = new HashMap<>();
        Matcher cellMatcher = CELL_PATTERN.matcher(xml);
        while (cellMatcher.find()) {
            int column = columnIndex(cellMatcher.group(1));
            int row = Integer.parseInt(cellMatcher.group(2));
            String type = cellMatcher.group(3);
            String body = cellMatcher.group(4);
            String value = cellValue(type, body, sharedStrings);
            rows.computeIfAbsent(row, ignored -> new HashMap<>()).put(column, value);
        }
        return rows.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(50)
                .map(entry -> normalizeRow(entry.getValue()))
                .toList();
    }

    private List<String> normalizeRow(Map<Integer, String> cells) {
        int max = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> row = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            row.add(cells.getOrDefault(i, ""));
        }
        return row;
    }

    private int columnIndex(String column) {
        int result = 0;
        for (char ch : column.toCharArray()) {
            result = result * 26 + (ch - 'A' + 1);
        }
        return result - 1;
    }

    private String cellValue(String type, String body, List<String> sharedStrings) {
        Matcher valueMatcher = VALUE_PATTERN.matcher(body);
        String raw = valueMatcher.find() ? valueMatcher.group(1) : "";
        if ("s".equals(type) && !raw.isBlank()) {
            int index = Integer.parseInt(raw);
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : raw;
        }
        if ("inlineStr".equals(type)) {
            Matcher inlineMatcher = INLINE_PATTERN.matcher(body);
            return inlineMatcher.find() ? unescapeXml(inlineMatcher.group(1)) : "";
        }
        return unescapeXml(raw);
    }

    private String toCsvPreview(List<List<String>> rows) {
        return rows.stream()
                .limit(8)
                .map(row -> String.join(",", row))
                .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
    }

    private String unescapeXml(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
