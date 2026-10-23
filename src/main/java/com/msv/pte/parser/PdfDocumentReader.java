package com.msv.pte.parser;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * PDFBox 2.x reader. Opens each document once and retains no PDFBox objects.
 * Coordinates use the displayed crop box: origin at the top left, x to the
 * right, y downward, after applying page rotation. No OCR is performed.
 */
public final class PdfDocumentReader {

    private static final long MEMORY_LIMIT = 32L * 1024 * 1024;

    private PdfDocumentReader() {
    }

    public static DocumentData read(File pdfFile) throws IOException {
        return read(
                Objects.requireNonNull(pdfFile, "pdfFile").toPath()
        );
    }

    public static DocumentData read(Path pdfPath) throws IOException {
        Path source = Objects.requireNonNull(pdfPath, "pdfPath")
                .toAbsolutePath()
                .normalize();

        if (!Files.isRegularFile(source)) {
            throw new IOException(
                    "PDF-Datei nicht gefunden: " + source
            );
        }

        try (PDDocument document = PDDocument.load(
                source.toFile(),
                MemoryUsageSetting.setupMixed(MEMORY_LIMIT)
        )) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException(
                        "Textextraktion ist für diese PDF-Datei nicht erlaubt."
                );
            }

            LayoutStripper stripper = new LayoutStripper();
            stripper.writeText(document, stripper.pageOutput);

            List<PageData> pages = new ArrayList<>();

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                int pageNumber = i + 1;
                PageData page = stripper.pages.get(pageNumber);

                if (page == null) {
                    page = pageData(
                            document.getPage(i),
                            pageNumber,
                            "",
                            List.of()
                    );
                }

                pages.add(page);

                if (!page.hasText()) {
                    stripper.problems.add(
                            "Seite " + pageNumber
                                    + " enthält keinen extrahierbaren Text."
                    );
                }
            }

            if (pages.isEmpty()) {
                stripper.problems.add(
                        "PDF enthält keine Seiten."
                );
            }

            String text = pages.stream()
                    .map(PageData::text)
                    .collect(Collectors.joining("\n\f\n"));

            if (!pages.isEmpty() && text.isBlank()) {
                stripper.problems.add(
                        "PDF ohne extrahierbaren Text; Bild-PDF oder leeres Dokument prüfen."
                );
            }

            return new DocumentData(
                    source,
                    text,
                    pages,
                    List.copyOf(stripper.problems)
            );
        }
    }

    public record DocumentData(
            Path source,
            String text,
            List<PageData> pages,
            List<String> problems
    ) {
        public DocumentData {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(text, "text");
            pages = List.copyOf(pages);
            problems = List.copyOf(problems);
        }

        public int pageCount() {
            return pages.size();
        }

        public boolean hasText() {
            return !text.isBlank();
        }

        public boolean requiresReview() {
            return !problems.isEmpty();
        }
    }

    public record PageData(
            int pageNumber,
            float width,
            float height,
            int rotation,
            String text,
            List<TextLine> lines
    ) {
        public PageData {
            Objects.requireNonNull(text, "text");
            lines = List.copyOf(lines);
        }

        public boolean hasText() {
            return !text.isBlank();
        }

        /**
         * Selects glyphs by their centers.
         * Right and bottom boundaries are exclusive.
         */
        public String textInArea(
                float left,
                float top,
                float right,
                float bottom
        ) {
            checkRange(left, right);
            checkRange(top, bottom);

            List<String> selected = new ArrayList<>();

            for (TextLine line : lines) {
                String value = join(
                        line.fragments(),
                        fragment ->
                                fragment.centerX() >= left
                                        && fragment.centerX() < right
                                        && fragment.centerY() >= top
                                        && fragment.centerY() < bottom
                );

                if (!value.isBlank()) {
                    selected.add(value);
                }
            }

            return String.join("\n", selected);
        }
    }

    public record TextLine(
            int pageNumber,
            int lineNumber,
            String text,
            List<TextFragment> fragments
    ) {
        public TextLine {
            Objects.requireNonNull(text, "text");
            fragments = List.copyOf(fragments);
        }

        public float left() {
            return (float) fragments.stream()
                    .mapToDouble(TextFragment::x)
                    .min()
                    .orElse(0);
        }

        public float top() {
            return (float) fragments.stream()
                    .mapToDouble(TextFragment::y)
                    .min()
                    .orElse(0);
        }

        public float right() {
            return (float) fragments.stream()
                    .mapToDouble(TextFragment::right)
                    .max()
                    .orElse(0);
        }

        public float bottom() {
            return (float) fragments.stream()
                    .mapToDouble(TextFragment::bottom)
                    .max()
                    .orElse(0);
        }

        public String textBetween(float left, float right) {
            checkRange(left, right);

            return join(
                    fragments,
                    fragment ->
                            fragment.centerX() >= left
                                    && fragment.centerX() < right
            );
        }
    }

    /**
     * One decoded glyph, possibly containing several Unicode characters.
     */
    public record TextFragment(
            String text,
            float x,
            float y,
            float width,
            float height,
            int wordIndex,
            int direction
    ) {
        public TextFragment {
            Objects.requireNonNull(text, "text");
        }

        public float right() {
            return x + width;
        }

        public float bottom() {
            return y + height;
        }

        public float centerX() {
            return x + width / 2;
        }

        public float centerY() {
            return y + height / 2;
        }
    }

    private static String join(
            List<TextFragment> fragments,
            Predicate<TextFragment> selected
    ) {
        StringBuilder result = new StringBuilder();
        int previousWord = -1;

        for (TextFragment fragment : fragments) {
            if (selected.test(fragment)) {
                if (previousWord >= 0
                        && previousWord != fragment.wordIndex()) {
                    result.append(' ');
                }

                result.append(fragment.text());
                previousWord = fragment.wordIndex();
            }
        }

        return result.toString().strip();
    }

    private static void checkRange(float start, float end) {
        if (!Float.isFinite(start)
                || !Float.isFinite(end)
                || end <= start) {
            throw new IllegalArgumentException(
                    "Ungültiger Bereich für die Textextraktion."
            );
        }
    }

    private static int angle(int degrees) {
        return Math.floorMod(degrees, 360);
    }

    private static PageData pageData(
            PDPage page,
            int pageNumber,
            String text,
            List<TextLine> lines
    ) throws IOException {
        PDRectangle box = page.getCropBox();

        int rotation = angle(page.getRotation());
        float width = box.getWidth();
        float height = box.getHeight();

        if (!Float.isFinite(width)
                || !Float.isFinite(height)
                || width <= 0
                || height <= 0
                || rotation % 90 != 0) {
            throw new IOException(
                    "Ungültige Seitengröße oder Drehung auf Seite "
                            + pageNumber + "."
            );
        }

        boolean sideways = rotation == 90 || rotation == 270;

        return new PageData(
                pageNumber,
                sideways ? height : width,
                sideways ? width : height,
                rotation,
                text,
                lines
        );
    }

    private record Bounds(
            float x,
            float y,
            float width,
            float height
    ) {
    }

    /**
     * Converts direction-adjusted glyph bounds into displayed page coordinates.
     */
    private static Bounds bounds(
            TextPosition position,
            int rotation
    ) {
        float x = position.getXDirAdj();
        float y = position.getYDirAdj();

        float width = position.getWidthDirAdj();
        float height = Math.max(position.getHeightDir(), 0.1f);

        float pageWidth = position.getPageWidth();
        float pageHeight = position.getPageHeight();

        Bounds raw = switch (angle(Math.round(position.getDir()))) {
            case 90 -> new Bounds(
                    y - height,
                    pageHeight - x - width,
                    height,
                    width
            );

            case 180 -> new Bounds(
                    pageWidth - x - width,
                    pageHeight - y,
                    width,
                    height
            );

            case 270 -> new Bounds(
                    pageWidth - y,
                    x,
                    height,
                    width
            );

            default -> new Bounds(
                    x,
                    y - height,
                    width,
                    height
            );
        };

        return switch (rotation) {
            case 90 -> new Bounds(
                    pageHeight - raw.y() - raw.height(),
                    raw.x(),
                    raw.height(),
                    raw.width()
            );

            case 180 -> new Bounds(
                    pageWidth - raw.x() - raw.width(),
                    pageHeight - raw.y() - raw.height(),
                    raw.width(),
                    raw.height()
            );

            case 270 -> new Bounds(
                    raw.y(),
                    pageWidth - raw.x() - raw.width(),
                    raw.height(),
                    raw.width()
            );

            default -> raw;
        };
    }

    private static final class LayoutStripper extends PDFTextStripper {

        private final StringWriter pageOutput = new StringWriter();

        private final Map<Integer, PageData> pages = new HashMap<>();
        private final Set<String> problems = new LinkedHashSet<>();

        private final List<TextLine> lines = new ArrayList<>();
        private final List<TextFragment> fragments = new ArrayList<>();

        private final StringBuilder lineText = new StringBuilder();

        private int rotation;
        private int wordIndex;

        private LayoutStripper() throws IOException {
            setSortByPosition(true);
            setShouldSeparateByBeads(false);
            setSuppressDuplicateOverlappingText(true);

            setLineSeparator("\n");
            setWordSeparator(" ");

            setParagraphStart("");
            setParagraphEnd("\n");

            setPageStart("");
            setPageEnd("");

            setArticleStart("");
            setArticleEnd("");
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);

            pageOutput.getBuffer().setLength(0);

            lines.clear();
            fragments.clear();

            lineText.setLength(0);
            wordIndex = 0;

            rotation = angle(page.getRotation());
        }

        @Override
        protected void writeString(
                String text,
                List<TextPosition> positions
        ) throws IOException {
            lineText.append(text);

            if (!text.isBlank() && positions.isEmpty()) {
                problem("Text ohne zugehörige Koordinaten.");
            }

            for (TextPosition position : positions) {
                String unicode = position.getUnicode();

                if (unicode == null || unicode.isEmpty()) {
                    problem("Textzeichen ohne Unicode-Abbildung.");
                    continue;
                }

                Bounds box = bounds(position, rotation);

                if (!Float.isFinite(box.x())
                        || !Float.isFinite(box.y())
                        || !Float.isFinite(box.width())
                        || !Float.isFinite(box.height())
                        || box.width() < 0
                        || box.height() < 0) {
                    problem("Ungültige Textkoordinaten.");
                    continue;
                }

                double orientation = Math.toDegrees(
                        Math.atan2(
                                position.getTextMatrix().getShearY(),
                                position.getTextMatrix().getScaleX()
                        )
                );

                if (Math.abs(
                        orientation - Math.rint(orientation / 90) * 90
                ) > 0.5) {
                    problem(
                            "Schräger Text; Koordinaten nur näherungsweise verfügbar."
                    );
                }

                fragments.add(
                        new TextFragment(
                                unicode,
                                box.x(),
                                box.y(),
                                box.width(),
                                box.height(),
                                wordIndex,
                                angle(
                                        rotation
                                                - Math.round(position.getDir())
                                )
                        )
                );
            }

            wordIndex++;
            super.writeString(text, positions);
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            lineText.append(' ');
            super.writeWordSeparator();
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            finishLine();
            super.writeLineSeparator();
        }

        @Override
        protected void writeParagraphEnd() throws IOException {
            finishLine();
            super.writeParagraphEnd();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            finishLine();

            pages.put(
                    getCurrentPageNo(),
                    pageData(
                            page,
                            getCurrentPageNo(),
                            pageOutput.toString(),
                            lines
                    )
            );
        }

        private void finishLine() {
            String value = lineText.toString().strip();

            if (!value.isEmpty() || !fragments.isEmpty()) {
                lines.add(
                        new TextLine(
                                getCurrentPageNo(),
                                lines.size() + 1,
                                value,
                                fragments
                        )
                );
            }

            lineText.setLength(0);
            fragments.clear();
            wordIndex = 0;
        }

        private void problem(String message) {
            problems.add(
                    "Seite " + getCurrentPageNo() + ": " + message
            );
        }
    }
}