package jp.example;

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NDLOCR-Liteが出力するJSONファイルを読む部品。
 * JSONには、OCRで読み取った「行ごとの文字」と「その行が画像のどこにあるか（座標）」が入っている。
 * 座標を使うと、表の行を選んだときに、画像の該当する場所へ自動で移動できる。
 *
 * JSONを読むための外部ライブラリ（jar）は使わず、必要な分だけの小さなJSON読み取り部品を中に持っている。
 */
public class OcrJson {

	/** OCRの1行分：文字と、画像の中での位置（元の画像のピクセル単位）、OCRの自信（信頼度）。 */
	public static class Line {
		public final String text;
		public final Rectangle rect;
		public final double confidence; // 0〜1（低いほど、読み間違いの可能性が高い）。不明なら-1

		Line(String text, Rectangle rect, double confidence) {
			this.text = text;
			this.rect = rect;
			this.confidence = confidence;
		}
	}

	/** JSONファイル全体の内容。 */
	public static class Data {
		public final List<Line> lines = new ArrayList<>();
		public int imageWidth; // OCRしたときの画像の幅（0なら不明）
		public int imageHeight;
		public String imageName = ""; // OCRした画像のファイル名
		public String imagePath = ""; // OCRしたときの画像の場所
	}

	/** JSONファイルを読み込む。形が違うときは、IOExceptionにして理由を知らせる。 */
	public static Data load(File file) throws IOException {
		String text;
		try {
			text = Files.readString(file.toPath());
		} catch (MalformedInputException e) {
			text = Files.readString(file.toPath(), Charset.forName("MS932"));
		}
		try {
			return fromJson(text);
		} catch (RuntimeException e) {
			throw new IOException("JSONの形式を読み取れませんでした（" + e.getMessage() + "）");
		}
	}

	@SuppressWarnings("unchecked")
	static Data fromJson(String json) {
		Object root = new Parser(json).parseAll();
		if (!(root instanceof Map)) {
			throw new IllegalArgumentException("先頭がオブジェクトではありません");
		}
		Map<String, Object> map = (Map<String, Object>) root;
		Data data = new Data();

		Object info = map.get("imginfo");
		if (info instanceof Map) {
			Map<String, Object> im = (Map<String, Object>) info;
			data.imageWidth = toInt(im.get("img_width"));
			data.imageHeight = toInt(im.get("img_height"));
			data.imageName = String.valueOf(im.getOrDefault("img_name", ""));
			data.imagePath = String.valueOf(im.getOrDefault("img_path", ""));
		}

		Object contents = map.get("contents");
		if (!(contents instanceof List)) {
			throw new IllegalArgumentException("contents（読み取り結果）がありません");
		}
		// contents は「ページのリスト」→「行のリスト」の二重のリスト
		for (Object page : (List<Object>) contents) {
			if (!(page instanceof List)) {
				continue;
			}
			for (Object item : (List<Object>) page) {
				if (!(item instanceof Map)) {
					continue;
				}
				Map<String, Object> line = (Map<String, Object>) item;
				Object t = line.get("text");
				Rectangle r = toRect(line.get("boundingBox"));
				Object cf = line.get("confidence");
				if (t instanceof String && r != null) {
					data.lines.add(new Line((String) t, r, cf instanceof Number ? ((Number) cf).doubleValue() : -1));
				}
			}
		}
		return data;
	}

	/** [[x,y],[x,y],[x,y],[x,y]] の4点から、それを囲む四角形を作る。 */
	@SuppressWarnings("unchecked")
	private static Rectangle toRect(Object box) {
		if (!(box instanceof List)) {
			return null;
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		int count = 0;
		for (Object p : (List<Object>) box) {
			if (p instanceof List && ((List<Object>) p).size() >= 2) {
				int x = toInt(((List<Object>) p).get(0));
				int y = toInt(((List<Object>) p).get(1));
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
				count++;
			}
		}
		return count == 0 ? null : new Rectangle(minX, minY, maxX - minX, maxY - minY);
	}

	private static int toInt(Object o) {
		return o instanceof Number ? ((Number) o).intValue() : 0;
	}

	/**
	 * 表の各行（解析に使ったOCRの1行の文字）に、画像の中の位置を対応づける。
	 * OCRの行と表の行は同じ順番に並んでいるので、前から順に、同じ文字の行を探していく。
	 * 見つからなかった行は、対応する位置なし（null）にする。
	 */
	public static List<Rectangle> matchBoxes(List<String> rawLines, List<Line> jsonLines) {
		List<Rectangle> result = new ArrayList<>();
		int from = 0;
		for (String raw : rawLines) {
			String key = normalize(raw);
			Rectangle found = null;
			for (int k = from; k < jsonLines.size(); k++) {
				if (normalize(jsonLines.get(k).text).equals(key)) {
					found = jsonLines.get(k).rect;
					from = k + 1;
					break;
				}
			}
			result.add(found);
		}
		return result;
	}

	private static String normalize(String s) {
		return s.replaceAll("[\\s　]+", "");
	}

	// ==================== 小さなJSON読み取り部品 ====================

	/** JSONの文字列を、Map（{}）・List（[]）・文字列・数値・true/false・nullに変える。 */
	private static class Parser {
		private final String s;
		private int i = 0;

		Parser(String s) {
			this.s = s.startsWith("\uFEFF") ? s.substring(1) : s; // 先頭の目印（BOM）があれば取り除く
		}

		Object parseAll() {
			Object v = parseValue();
			skipSpaces();
			if (i < s.length()) {
				throw error("余分な文字があります");
			}
			return v;
		}

		private Object parseValue() {
			skipSpaces();
			if (i >= s.length()) {
				throw error("途中で終わっています");
			}
			char c = s.charAt(i);
			if (c == '{') {
				return parseObject();
			}
			if (c == '[') {
				return parseArray();
			}
			if (c == '"') {
				return parseString();
			}
			if (s.startsWith("true", i)) {
				i += 4;
				return Boolean.TRUE;
			}
			if (s.startsWith("false", i)) {
				i += 5;
				return Boolean.FALSE;
			}
			if (s.startsWith("null", i)) {
				i += 4;
				return null;
			}
			return parseNumber();
		}

		private Map<String, Object> parseObject() {
			Map<String, Object> map = new LinkedHashMap<>();
			i++; // {
			skipSpaces();
			if (peek() == '}') {
				i++;
				return map;
			}
			while (true) {
				skipSpaces();
				String key = parseString();
				skipSpaces();
				expect(':');
				map.put(key, parseValue());
				skipSpaces();
				char c = next();
				if (c == '}') {
					return map;
				}
				if (c != ',') {
					throw error("「,」か「}」が必要です");
				}
			}
		}

		private List<Object> parseArray() {
			List<Object> list = new ArrayList<>();
			i++; // [
			skipSpaces();
			if (peek() == ']') {
				i++;
				return list;
			}
			while (true) {
				list.add(parseValue());
				skipSpaces();
				char c = next();
				if (c == ']') {
					return list;
				}
				if (c != ',') {
					throw error("「,」か「]」が必要です");
				}
			}
		}

		private String parseString() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (i >= s.length()) {
					throw error("文字列が閉じていません");
				}
				char c = s.charAt(i++);
				if (c == '"') {
					return sb.toString();
				}
				if (c != '\\') {
					sb.append(c);
					continue;
				}
				char e = next();
				switch (e) {
				case '"' -> sb.append('"');
				case '\\' -> sb.append('\\');
				case '/' -> sb.append('/');
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'u' -> {
					if (i + 4 > s.length()) {
						throw error("\\u の後ろが足りません");
					}
					sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
					i += 4;
				}
				default -> throw error("知らない「\\" + e + "」があります");
				}
			}
		}

		private Double parseNumber() {
			int start = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
				i++;
			}
			if (start == i) {
				throw error("読み取れない文字「" + s.charAt(i) + "」があります");
			}
			return Double.valueOf(s.substring(start, i));
		}

		private void skipSpaces() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}

		private char peek() {
			return i < s.length() ? s.charAt(i) : '\0';
		}

		private char next() {
			if (i >= s.length()) {
				throw error("途中で終わっています");
			}
			return s.charAt(i++);
		}

		private void expect(char c) {
			if (next() != c) {
				throw error("「" + c + "」が必要です");
			}
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException(message + "（" + i + "文字目）");
		}
	}
}
