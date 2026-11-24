package com.iflytek.astron.console.hub.service.chat.springai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.toolkit.util.S3Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code export_file}: lets a team lead deliver a finished file (Word / Excel / PowerPoint /
 * Markdown / CSV). The file is generated in-process with Apache POI, stored in object storage, and
 * its download link is returned to the model and recorded in the trace.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportFileToolFactory {

    static final String TOOL_NAME = "export_file";
    private static final int MAX_CONTENT_CHARS = 200_000;
    private static final int MAX_ROWS = 5_000;
    private static final int MAX_SLIDES = 50;
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern NUMBERED = Pattern.compile("^\\d+[.、)]\\s+.*");

    private static final String INPUT_SCHEMA =
            """
                    {"type":"object","properties":{
                    "format":{"type":"string","enum":["docx","xlsx","pptx","md","csv"],"description":"docx 文档 / xlsx 表格 / pptx 演示文稿 / md / csv"},
                    "fileName":{"type":"string","description":"文件名，不含扩展名"},
                    "content":{"type":"string","description":"docx 和 md 的正文，Markdown：# 标题、- 列表、1. 编号、| 表格 |、**加粗**"},
                    "sheets":{"type":"array","description":"xlsx 和 csv 的数据，每个工作表一项（csv 只用第一个）","items":{"type":"object","properties":{"name":{"type":"string"},"rows":{"type":"array","items":{"type":"array","items":{"type":"string"}},"description":"第一行是表头"}}}},
                    "slides":{"type":"array","description":"pptx 的幻灯片","items":{"type":"object","properties":{"title":{"type":"string"},"bullets":{"type":"array","items":{"type":"string"}}}}}
                    },"required":["format","fileName"]}""";

    private final S3Util s3Util;

    public ToolCallback create(ChatToolContext context) {
        return new ExportFileTool(context);
    }

    private final class ExportFileTool implements ToolCallback {

        private final ChatToolContext context;

        ExportFileTool(ChatToolContext context) {
            this.context = context;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(TOOL_NAME)
                    .description("生成可下载的成品文件（Word 文档、Excel 表格、PowerPoint 演示文稿、Markdown 或 CSV），返回下载链接。"
                            + "docx/md 用 content，xlsx/csv 用 sheets，pptx 用 slides。")
                    .inputSchema(INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            long start = System.currentTimeMillis();
            JSONObject args;
            try {
                args = JSON.parseObject(toolInput);
            } catch (Exception e) {
                return "生成文件失败：参数不是合法的 JSON。";
            }
            if (args == null) {
                return "生成文件失败：缺少参数。";
            }
            String format = StringUtils.lowerCase(StringUtils.trimToEmpty(args.getString("format")), Locale.ROOT);
            String baseName = safeName(args.getString("fileName"));
            try {
                byte[] bytes;
                String contentType;
                switch (format) {
                    case "docx" -> {
                        bytes = docx(limit(args.getString("content")));
                        contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    }
                    case "xlsx" -> {
                        bytes = xlsx(args.getJSONArray("sheets"));
                        contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    }
                    case "pptx" -> {
                        bytes = pptx(baseName, args.getJSONArray("slides"));
                        contentType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    }
                    case "md" -> {
                        bytes = limit(args.getString("content")).getBytes(StandardCharsets.UTF_8);
                        contentType = "text/markdown; charset=utf-8";
                    }
                    case "csv" -> {
                        bytes = csv(args.getJSONArray("sheets"));
                        contentType = "text/csv; charset=utf-8";
                    }
                    default -> {
                        return "生成文件失败：format 只能是 docx / xlsx / pptx / md / csv。";
                    }
                }
                String fileName = baseName + "." + format;
                String key = "agent-files/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "/"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + fileName;
                s3Util.putObject(key, bytes, contentType);
                String url = s3Util.getS3Url(key);
                log.info("Export file created, format={}, fileName={}, bytes={}, costMs={}", format, fileName,
                        bytes.length, System.currentTimeMillis() - start);
                context.addTrace(new JSONObject()
                        .fluentPut("type", "file")
                        .fluentPut("deskToolName", "生成文件：" + fileName)
                        .fluentPut("toolName", TOOL_NAME)
                        .fluentPut("fileName", fileName)
                        .fluentPut("format", format)
                        .fluentPut("size", bytes.length)
                        .fluentPut("url", url));
                context.emitTeam(new JSONObject()
                        .fluentPut("kind", "file")
                        .fluentPut("fileName", fileName)
                        .fluentPut("format", format)
                        .fluentPut("size", bytes.length)
                        .fluentPut("url", url));
                return "文件已生成：" + fileName + "\n下载链接：" + url + "\n请把下载链接以 Markdown 链接形式告诉用户。";
            } catch (IllegalArgumentException e) {
                return "生成文件失败：" + e.getMessage();
            } catch (Exception e) {
                log.warn("Export file failed, format={}, errorType={}, msg={}", format, e.getClass().getSimpleName(),
                        e.getMessage());
                return "生成文件失败：" + e.getClass().getSimpleName();
            }
        }
    }

    private static String limit(String content) {
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("content 为空");
        }
        return content.length() > MAX_CONTENT_CHARS ? content.substring(0, MAX_CONTENT_CHARS) : content;
    }

    static String safeName(String name) {
        String cleaned = StringUtils.defaultString(name).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        cleaned = StringUtils.strip(cleaned, "._");
        return StringUtils.left(StringUtils.defaultIfBlank(cleaned, "文件"), 80);
    }

    // ------------------------------------------------------------------ docx

    static byte[] docx(String markdown) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<String[]> table = new ArrayList<>();
            for (String raw : markdown.split("\\r?\\n")) {
                String line = raw.strip();
                if (line.startsWith("|")) {
                    if (!line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                        table.add(splitRow(line));
                    }
                    continue;
                }
                flushTable(doc, table);
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("#")) {
                    int level = Math.min(3, line.length() - line.replaceFirst("^#+", "").length());
                    XWPFRun run = doc.createParagraph().createRun();
                    run.setBold(true);
                    run.setFontSize(level == 1 ? 18 : level == 2 ? 15 : 13);
                    run.setText(line.replaceFirst("^#+\\s*", ""));
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    writeInline(doc.createParagraph(), "• " + line.substring(2));
                } else if (NUMBERED.matcher(line).matches()) {
                    writeInline(doc.createParagraph(), line);
                } else if (line.startsWith("> ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(400);
                    writeInline(p, line.substring(2));
                } else {
                    writeInline(doc.createParagraph(), line);
                }
            }
            flushTable(doc, table);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static String[] splitRow(String line) {
        String inner = StringUtils.removeEnd(StringUtils.removeStart(line, "|"), "|");
        String[] cells = inner.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].strip();
        }
        return cells;
    }

    private static void flushTable(XWPFDocument doc, List<String[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int cols = rows.stream().mapToInt(r -> r.length).max().orElse(1);
        XWPFTable table = doc.createTable(rows.size(), cols);
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            for (int c = 0; c < cols; c++) {
                String text = c < rows.get(r).length ? rows.get(r)[c] : "";
                XWPFParagraph p = row.getCell(c).getParagraphs().get(0);
                XWPFRun run = p.createRun();
                run.setBold(r == 0);
                run.setText(text.replace("**", ""));
            }
        }
        doc.createParagraph();
        rows.clear();
    }

    /** Writes text with **bold** spans. */
    private static void writeInline(XWPFParagraph paragraph, String text) {
        Matcher m = BOLD.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                paragraph.createRun().setText(text.substring(last, m.start()));
            }
            XWPFRun bold = paragraph.createRun();
            bold.setBold(true);
            bold.setText(m.group(1));
            last = m.end();
        }
        if (last < text.length()) {
            paragraph.createRun().setText(text.substring(last));
        }
    }

    // ------------------------------------------------------------------ xlsx / csv

    static byte[] xlsx(JSONArray sheets) throws IOException {
        if (sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException("sheets 为空");
        }
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = book.createCellStyle();
            Font font = book.createFont();
            font.setBold(true);
            header.setFont(font);
            int rowBudget = MAX_ROWS;
            for (int s = 0; s < sheets.size(); s++) {
                JSONObject spec = sheets.getJSONObject(s);
                String name = StringUtils.left(StringUtils.defaultIfBlank(
                        spec == null ? null : spec.getString("name"), "Sheet" + (s + 1)).replaceAll("[\\\\/*?:\\[\\]]", "_"),
                        31);
                Sheet sheet = book.createSheet(uniqueSheetName(book, name));
                JSONArray rows = spec == null ? null : spec.getJSONArray("rows");
                if (rows == null) {
                    continue;
                }
                int maxCols = 0;
                for (int r = 0; r < rows.size() && rowBudget-- > 0; r++) {
                    JSONArray cells = rows.getJSONArray(r);
                    Row row = sheet.createRow(r);
                    if (cells == null) {
                        continue;
                    }
                    maxCols = Math.max(maxCols, cells.size());
                    for (int c = 0; c < cells.size(); c++) {
                        Cell cell = row.createCell(c);
                        String value = cells.getString(c);
                        if (r > 0 && value != null && value.matches("-?\\d+(\\.\\d+)?")) {
                            cell.setCellValue(Double.parseDouble(value));
                        } else {
                            cell.setCellValue(StringUtils.defaultString(value));
                        }
                        if (r == 0) {
                            cell.setCellStyle(header);
                        }
                    }
                }
                for (int c = 0; c < Math.min(maxCols, 30); c++) {
                    sheet.setColumnWidth(c, 18 * 256);
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private static String uniqueSheetName(XSSFWorkbook book, String name) {
        String candidate = name;
        for (int i = 2; book.getSheet(candidate) != null; i++) {
            candidate = StringUtils.left(name, 28) + "_" + i;
        }
        return candidate;
    }

    static byte[] csv(JSONArray sheets) {
        if (sheets == null || sheets.isEmpty() || sheets.getJSONObject(0) == null) {
            throw new IllegalArgumentException("sheets 为空");
        }
        JSONArray rows = sheets.getJSONObject(0).getJSONArray("rows");
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("rows 为空");
        }
        // BOM so Excel opens UTF-8 Chinese text correctly.
        StringBuilder sb = new StringBuilder("\uFEFF");
        for (int r = 0; r < Math.min(rows.size(), MAX_ROWS); r++) {
            JSONArray cells = rows.getJSONArray(r);
            List<String> escaped = new ArrayList<>();
            if (cells != null) {
                for (int c = 0; c < cells.size(); c++) {
                    String v = StringUtils.defaultString(cells.getString(c));
                    escaped.add(v.matches(".*[\",\\n].*") ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
                }
            }
            sb.append(String.join(",", escaped)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ pptx

    static byte[] pptx(String title, JSONArray slides) throws IOException {
        if (slides == null || slides.isEmpty()) {
            throw new IllegalArgumentException("slides 为空");
        }
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlideLayout titleLayout = show.getSlideMasters().get(0).getLayout(SlideLayout.TITLE);
            XSLFSlideLayout contentLayout = show.getSlideMasters().get(0).getLayout(SlideLayout.TITLE_AND_CONTENT);
            XSLFSlide cover = show.createSlide(titleLayout);
            cover.getPlaceholder(0).setText(title);
            XSLFTextShape subtitle = cover.getPlaceholder(1);
            if (subtitle != null) {
                subtitle.setText(LocalDate.now().toString());
            }
            for (int i = 0; i < Math.min(slides.size(), MAX_SLIDES); i++) {
                JSONObject spec = slides.getJSONObject(i);
                if (spec == null) {
                    continue;
                }
                XSLFSlide slide = show.createSlide(contentLayout);
                slide.getPlaceholder(0).setText(StringUtils.defaultString(spec.getString("title")));
                XSLFTextShape body = slide.getPlaceholder(1);
                JSONArray bullets = spec.getJSONArray("bullets");
                if (body != null) {
                    body.clearText();
                    if (bullets != null) {
                        for (int b = 0; b < bullets.size(); b++) {
                            body.addNewTextParagraph()
                                    .addNewTextRun()
                                    .setText(StringUtils.defaultString(bullets.getString(b)).replace("**", ""));
                        }
                    }
                }
            }
            show.write(out);
            return out.toByteArray();
        }
    }
}
