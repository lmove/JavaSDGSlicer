package es.upv.mist.slicing.cli;

import es.upv.mist.slicing.arcs.Arc;
import es.upv.mist.slicing.graphs.Graph;
import es.upv.mist.slicing.nodes.GraphNode;
import es.upv.mist.slicing.utils.Logger;
import org.jgrapht.nio.dot.DOTExporter;

import java.awt.*;
import java.io.*;

public abstract class GraphLog<G extends Graph> {
    public enum Format {
        PNG("png"),
        PDF("pdf");

        private String ext;
        Format(String ext) {
            this.ext = ext;
        }

        public String getExt() {
            return ext;
        }
    }

    protected G graph;

    protected String imageName;
    protected String format;
    protected boolean generated = false;
    protected File outputDir = new File("./out/");

    public GraphLog() {
        this(null);
    }

    public GraphLog(G graph) {
        this.graph = graph;
    }

    public void setDirectory(File outputDir) {
        this.outputDir = outputDir;
    }

    public void log() throws IOException {
        Logger.log(
                "****************************\n" +
                "*           GRAPH          *\n" +
                "****************************"
        );
        Logger.log(graph);
        Logger.log(
                "****************************\n" +
                "*         GRAPHVIZ         *\n" +
                "****************************"
        );
        try (StringWriter stringWriter = new StringWriter()) {
            getDOTExporter(graph).exportGraph(graph, stringWriter);
            stringWriter.append('\n');
            Logger.log(stringWriter.toString());
        }
    }

    public void generateImages() throws IOException {
        generateImages("graph");
    }

    public void generateImages(String imageName) throws IOException {
        generateImages(imageName, "pdf");
    }

    public void generateImages(String imageName, String format) throws IOException {
        this.imageName = imageName + "-" + graph.getClass().getSimpleName();
        this.format = format;
        generated = true;
        File tmpDot = File.createTempFile("graph-source-", ".dot");
        tmpDot.getParentFile().mkdirs();
        getImageFile().getParentFile().mkdirs();

        // Graph -> DOT -> file
        try (Writer w = new FileWriter(tmpDot)) {
            getDOTExporter(graph).exportGraph(graph, w);
        }
        // Execute dot
        ProcessBuilder pb = new ProcessBuilder("dot",
            tmpDot.getAbsolutePath(), "-T" + format,
            "-o", getImageFile().getAbsolutePath());
        try {
            int result = pb.start().waitFor();
            if (result == 0)
                tmpDot.deleteOnExit();
            else
                Logger.log("Image generation failed, try running \"" + pb.toString() + "\" on your terminal.");
        } catch (InterruptedException e) {
            Logger.log("Image generation failed\n" + e.getMessage());
        }
    }

    public void openVisualRepresentation() throws IOException {
        if (!generated) generateImages();
        openFileForUser(getImageFile());
    }

    protected static void openFileForUser(File file) throws IOException {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file);
            return;
        }
        // Alternative manual opening of the file
        String os = System.getProperty("os.name").toLowerCase();
        String cmd = null;
        if (os.contains("win")) {
            cmd = "";
        } else if (os.contains("mac")) {
            cmd = "open";
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            cmd = "xdg-open";
        }

        if (cmd != null) {
            new ProcessBuilder(cmd, file.getAbsolutePath()).start();
        } else {
            Logger.format("Warning: cannot open file %s in your system (%s)",
                    file.getName(), os);
        }
    }

    public File getImageFile() {
        return new File(outputDir, imageName + "." + format);
    }

    protected DOTExporter<GraphNode<?>, Arc> getDOTExporter(G graph) {
        return graph.getDOTExporter();
    }
}
