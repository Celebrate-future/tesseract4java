package de.vorb.tesseract.gui.controller;

import java.awt.Cursor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import org.bridj.BridJ;

import de.vorb.tesseract.bridj.Tesseract.TessPageIteratorLevel;
import de.vorb.tesseract.gui.event.PageChangeListener;
import de.vorb.tesseract.gui.event.ProjectChangeListener;
import de.vorb.tesseract.gui.view.TesseractFrame;
import de.vorb.tesseract.tools.recognition.DefaultRecognitionConsumer;
import de.vorb.tesseract.tools.recognition.RecognitionState;
import de.vorb.tesseract.util.Box;
import de.vorb.tesseract.util.Line;
import de.vorb.tesseract.util.Page;
import de.vorb.tesseract.util.Project;
import de.vorb.tesseract.util.Symbol;
import de.vorb.tesseract.util.Word;

public class TesseractController implements ProjectChangeListener,
        PageChangeListener {

    private final TesseractFrame view;
    private SwingWorker<Page, Void> pageLoaderWorker = null;
    private PageLoader pageLoader = null;

    // Filter for image files
    private static final DirectoryStream.Filter<Path> IMG_FILTER =
            new DirectoryStream.Filter<Path>() {
                @Override
                public boolean accept(Path entry) throws IOException {
                    return entry.toString().endsWith(".png")
                            || entry.toString().endsWith(".tif")
                            || entry.toString().endsWith(".tiff")
                            || entry.toString().endsWith(".jpg")
                            || entry.toString().endsWith(".jpeg");
                }
            };

    public static void main(String[] args) {
        BridJ.setNativeLibraryFile("tesseract", new File("libtesseract303.dll"));

        new TesseractController();
    }

    public TesseractController() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
        }

        view = new TesseractFrame();
        view.getLoadProjectDialog().addProjectChangeListener(this);

        try {
            pageLoader = new PageLoader();
        } catch (Exception e) {
            e.printStackTrace();
        }

        view.setVisible(true);
    }

    @Override
    public void projectChanged(Path scanDir) {
        try {
            final ArrayList<Path> pages = new ArrayList<>();

            final Iterator<Path> dirIt = Files.newDirectoryStream(scanDir,
                    IMG_FILTER).iterator();

            while (dirIt.hasNext()) {
                final Path file = dirIt.next();

                if (Files.isDirectory(file))
                    continue;

                pages.add(file);
            }

            final Project project = new Project(scanDir, pages);
            project.addPageChangeListener(this);

            view.getPageSelectionPane().setModel(project);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void pageChanged(int pageIndex, final Path page) {
        view.getPageLoadProgressBar().setIndeterminate(true);
        view.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        pageLoaderWorker = new SwingWorker<Page, Void>() {
            @Override
            protected Page doInBackground() {
                try {
                    return loadPageModel(page);
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            public void done() {
                try {
                    final Page page = get();
                    view.getComparatorPane().setModel(page);
                    view.getPageLoadProgressBar().setIndeterminate(false);
                    view.setCursor(Cursor.getDefaultCursor());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        };

        pageLoaderWorker.execute();
    }

    private Page loadPageModel(Path scanFile) throws IOException {
        pageLoader.reset();

        final Vector<Line> lines = new Vector<Line>();

        // Get images
        final BufferedImage originalImg = ImageIO.read(scanFile.toFile());
        pageLoader.setOriginalImage(originalImg);
        final BufferedImage thresholdedImg = pageLoader.getThresholdedImage();

        pageLoader.recognize(new DefaultRecognitionConsumer() {
            private ArrayList<Word> lineWords;
            private ArrayList<Symbol> wordSymbols;

            @Override
            public void lineBegin() {
                lineWords = new ArrayList<>();
            }

            @Override
            public void lineEnd() {
                final TessPageIteratorLevel level = TessPageIteratorLevel.RIL_TEXTLINE;
                lines.add(new Line(getState().getBoundingBox(level), lineWords,
                        getState().getBaseline(level)));
            }

            @Override
            public void wordBegin() {
                wordSymbols = new ArrayList<>();
            }

            @Override
            public void wordEnd() {
                final RecognitionState state = getState();
                final TessPageIteratorLevel level = TessPageIteratorLevel.RIL_WORD;
                final Box bbox = state.getBoundingBox(level);
                lineWords.add(new Word(wordSymbols, bbox,
                        state.getConfidence(level),
                        state.getBaseline(TessPageIteratorLevel.RIL_WORD),
                        state.getWordFontAttributes()));
            }

            @Override
            public void symbol() {
                final TessPageIteratorLevel level = TessPageIteratorLevel.RIL_SYMBOL;
                wordSymbols.add(new Symbol(getState().getText(level),
                        getState().getBoundingBox(level),
                        getState().getConfidence(level)));
            }
        });

        final Page result = new Page(scanFile, originalImg, thresholdedImg,
                lines);

        return result;
    }
}