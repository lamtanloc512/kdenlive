package dev.kdenlivemcp.backend.kdenlive;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small DOM helpers (JDK only, so no reflection setup is needed for native images). */
public final class Xml {

    private Xml() {
    }

    public static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            return f.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("XML parser unavailable", e);
        }
    }

    public static Document newDocument() {
        return builder().newDocument();
    }

    public static Document parse(Path file) {
        try {
            return builder().parse(file.toFile());
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot parse " + file.getFileName() + " as XML: " + e.getMessage(), e);
        }
    }

    public static Document parseString(String xml) {
        try {
            return builder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid XML: " + e.getMessage(), e);
        }
    }

    /** Child elements with the given tag (or all child elements if tag is null). */
    public static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && (tag == null || e.getTagName().equals(tag))) {
                out.add(e);
            }
        }
        return out;
    }

    /** {@code <property name=…>} children as an ordered map. */
    public static Map<String, String> properties(Element parent) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Element p : children(parent, "property")) {
            map.put(p.getAttribute("name"), p.getTextContent());
        }
        return map;
    }

    public static String prop(Element parent, String name) {
        for (Element p : children(parent, "property")) {
            if (p.getAttribute("name").equals(name)) {
                return p.getTextContent();
            }
        }
        return null;
    }

    public static Element el(Element parent, String tag, String... attrs) {
        Element e = parent.getOwnerDocument().createElement(tag);
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            if (attrs[i + 1] != null) {
                e.setAttribute(attrs[i], attrs[i + 1]);
            }
        }
        parent.appendChild(e);
        return e;
    }

    /** Adds {@code <property name="name">value</property>}; null or "" writes an empty element. */
    public static void prop(Element parent, String name, Object value) {
        Element p = parent.getOwnerDocument().createElement("property");
        p.setAttribute("name", name);
        if (value != null && !value.toString().isEmpty()) {
            p.setTextContent(value.toString());
        }
        parent.appendChild(p);
    }

    public static String toString(Node node, boolean declaration) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, declaration ? "no" : "yes");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "1");
            t.setOutputProperty(OutputKeys.ENCODING, "utf-8");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize XML", e);
        }
    }

    /** Re-imports a serialized element (passthrough data) under {@code parent}. */
    public static void appendFragment(Element parent, String xml) {
        Element frag = parseString(xml).getDocumentElement();
        parent.appendChild(parent.getOwnerDocument().importNode(frag, true));
    }

    /** Writes atomically: temp file in the same directory, then move. */
    public static void writeAtomically(Path file, String content) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
            Files.writeString(tmp, content);
            try { // temp files are created 0600; keep the usual rw-r--r-- (or the existing file's mode)
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = Files.exists(file)
                        ? Files.getPosixFilePermissions(file)
                        : java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
                Files.setPosixFilePermissions(tmp, perms);
            } catch (UnsupportedOperationException | IOException ignored) {
                // non-POSIX file system (Windows)
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
