package money.hejje.news.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import money.hejje.news.NewsSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** RSS 2.0, Atom and a simple JSON array ({@code [{title, url, summary, published}]}) into fetched items. Pure and lenient. */
public final class FeedParser {

    private FeedParser() {
    }

    public record Fetched(String url, String title, String summary, Instant publishedAt) {}

    public static List<Fetched> parse(NewsSource.Kind kind, String body, Instant fallbackTime) {
        try {
            return switch (kind) {
                case RSS -> rss(body, fallbackTime);
                case ATOM -> atom(body, fallbackTime);
                case JSON -> json(body, fallbackTime);
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable " + kind + " feed: " + e.getMessage(), e);
        }
    }

    private static Document xml(String body) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(true);
        String trimmed = body.replace("\uFEFF", "").stripLeading(); // BOMs and leading whitespace before the XML declaration are common
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Fetched> rss(String body, Instant fallback) throws Exception {
        List<Fetched> out = new ArrayList<>();
        NodeList items = xml(body).getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String link = text(item, "link");
            String title = text(item, "title");
            if (link.isBlank() || title.isBlank()) {
                continue;
            }
            out.add(new Fetched(link.trim(), clean(title), clean(text(item, "description")), rfc1123(text(item, "pubDate"), fallback)));
        }
        return out;
    }

    private static List<Fetched> atom(String body, Instant fallback) throws Exception {
        List<Fetched> out = new ArrayList<>();
        NodeList entries = xml(body).getElementsByTagNameNS("*", "entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String link = null;
            NodeList links = entry.getElementsByTagNameNS("*", "link");
            for (int j = 0; j < links.getLength(); j++) {
                Element l = (Element) links.item(j);
                if (link == null || "alternate".equals(l.getAttribute("rel"))) {
                    link = l.getAttribute("href");
                }
            }
            String title = textNs(entry, "title");
            if (link == null || link.isBlank() || title.isBlank()) {
                continue;
            }
            String summary = textNs(entry, "summary");
            if (summary.isBlank()) {
                summary = textNs(entry, "content");
            }
            String when = textNs(entry, "published");
            if (when.isBlank()) {
                when = textNs(entry, "updated");
            }
            out.add(new Fetched(link.trim(), clean(title), clean(summary), iso(when, fallback)));
        }
        return out;
    }

    private static List<Fetched> json(String body, Instant fallback) throws Exception {
        List<Fetched> out = new ArrayList<>();
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode array = root.isArray() ? root : root.path("items");
        for (JsonNode n : array) {
            String url = n.path("url").asText(n.path("link").asText(""));
            String title = n.path("title").asText("");
            if (url.isBlank() || title.isBlank()) {
                continue;
            }
            out.add(new Fetched(url, clean(title), clean(n.path("summary").asText(n.path("description").asText(""))), iso(n.path("published").asText(""), fallback)));
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getParentNode() == parent) {
                return n.getTextContent() == null ? "" : n.getTextContent();
            }
        }
        return "";
    }

    private static String textNs(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS("*", tag);
        return nodes.getLength() == 0 || nodes.item(0).getTextContent() == null ? "" : nodes.item(0).getTextContent();
    }

    /** Strips tags and collapses whitespace (feeds often embed HTML in descriptions). */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
    }

    static Instant rfc1123(String s, Instant fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            return iso(s, fallback);
        }
    }

    static Instant iso(String s, Instant fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(s.trim());
            } catch (RuntimeException e2) {
                return fallback;
            }
        }
    }
}
