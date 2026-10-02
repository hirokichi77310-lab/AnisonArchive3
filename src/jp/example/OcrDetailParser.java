package jp.example;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 詳細ページ（作品ごとに、主題歌の表とレコード・CDの一覧がのっているページ）のOCR結果から、
 * 「曲」と「音源」の候補を作る部品。
 *
 * 文字だけでなく、各行の座標（画像のどこにあるか）も使う。
 * ・曲名：作品名の下に並んだ行
 * ・作詞者・作曲者・編曲者・歌唱者：表の列を、行の位置から見つけ、表の行に対応づける（「〃」は上と同じ）
 * ・音源：品番や日付の行をきっかけに、1件ずつに区切る
 * 写真は少し傾いていることが多いので、傾きを推定して、位置をそろえてから比べる。
 *
 * OCRは、影になった部分や小さな記号を読めないことがある。読めなかったところは空欄にして、
 * 「注意メモ」に理由を書く（黙って埋めない）。最終的な判断は、人が元の画像と見比べて行う。
 */
public class OcrDetailParser {

	/** 主題歌1件分の候補。 */
	public static class SongRow {
		public String kubun = "";
		public String title = "";
		public String lyricist = "";
		public String composer = "";
		public String arranger = "";
		public String singer = "";
		public String note = "";
		public String memo = ""; // 注意メモ
		public Rectangle source; // 画像の中の位置（曲名の行）
	}

	/** 音源（レコード・CD・カセットなど）1件分の候補。 */
	public static class CdRow {
		public String title = "";
		public String tracks = "";
		public String company = "";
		public String hinban = "";
		public String note = "";
		public String memo = "";
		public Rectangle source; // 画像の中の位置（この音源の行全体）
	}

	/** 解析結果。 */
	public static class Result {
		public String workTitleGuess = ""; // 見出しから読み取った作品名（欠けていることがある）
		public int pageYear = -1; // 「【74-1】」のような目印から推定した年（分からなければ-1）
		public final List<SongRow> songs = new ArrayList<>();
		public final List<CdRow> cds = new ArrayList<>();
		public final List<String> messages = new ArrayList<>();
	}

	/** 計算に使うために、OCRの1行に、位置などの数値を付けたもの。 */
	private static class L {
		final OcrJson.Line src;
		final String t;
		final int len;
		final double cx, cy, w, h, left;
		double yRef; // 傾きを直して、同じ基準の位置にそろえたときの高さ

		L(OcrJson.Line src) {
			this.src = src;
			this.t = src.text.trim();
			this.len = t.replaceAll("[\\s　]", "").length();
			Rectangle r = src.rect;
			this.cx = r.x + r.width / 2.0;
			this.cy = r.y + r.height / 2.0;
			this.w = r.width;
			this.h = r.height;
			this.left = r.x;
		}
	}

	private static final Pattern DISCO_MARK = Pattern
			.compile("[※●]|セット絵本|\\(\\s*\\d{1,2}\\s*\\.|[A-Za-z]{1,4}-?\\d{3,}|収録");
	private static final Pattern DATE_PAREN = Pattern.compile("\\(\\s*(\\d{1,2})?\\s*[.．]\\s*(\\d{1,2})?\\s*\\)");
	// 品番は、前後が日本語や英数字でつながっていないものだけ（「菱KKS-4098の再版」のような文中の品番は、備考の文なので除く）
	private static final Pattern CODE_LETTERS = Pattern.compile("(?<![\\p{L}\\p{N}])[A-Za-z]{1,4}\\d*-?\\d{1,8}(?:-[A-Za-z]{1,3})?(?![\\p{L}\\p{N}])");
	private static final Pattern CODE_DIGITS = Pattern.compile("(?<![\\p{L}\\p{N}])\\d{1,2}-\\d{3,5}(?![\\p{L}\\p{N}])");
	private static final Pattern TRACKS = Pattern.compile("(\\d+\\s*[~〜～]\\s*\\d+|\\d+(?:\\s*[,，]\\s*\\d+)*)\\s*$");

	private static final int ROW_MERGE = 16; // 同じ行とみなす、高さの差（ピクセル）
	private static final int ZONE_GAP = 75; // これ以上あくと、音源の一覧が終わったとみなす（ピクセル）

	/**
	 * @param pageLines ページ全体のOCRの行（傾きの推定に使う）
	 * @param region    解析する範囲（画像の座標）。nullならページ全体
	 * @param yearHint  年の手がかり（例："1974"）。空でもよい
	 */
	public static Result parse(List<OcrJson.Line> pageLines, Rectangle region, String yearHint) {
		Result res = new Result();
		res.pageYear = findPageYear(pageLines, yearHint);

		// ---- 計算用の行を作る ----
		List<L> all = new ArrayList<>();
		for (OcrJson.Line line : pageLines) {
			L l = new L(line);
			if (l.len > 0) {
				all.add(l);
			}
		}
		double dxdy = estimateDrift(all);
		double slope = -dxdy; // 右へ行くほど、どれだけ上がるか（上がるときはマイナス）
		double xRef = 700;
		for (L l : all) {
			l.yRef = l.cy + slope * (xRef - l.cx);
		}
		if (Math.abs(dxdy) < 0.005) {
			res.messages.add("ページの傾きを推定できなかったため、傾きなしとして扱いました。表の対応がずれている場合は、手で直してください。");
		}

		// ---- 解析する範囲の行だけにする ----
		List<L> ls = new ArrayList<>();
		for (L l : all) {
			if (region == null || region.contains(l.cx, l.cy)) {
				ls.add(l);
			}
		}
		ls.removeIf(l -> l.t.startsWith("【")); // 「【74-1】」などのページの目印
		ls.sort(Comparator.comparingDouble(l -> l.yRef));
		if (ls.isEmpty()) {
			res.messages.add("解析する範囲に、OCRの行がありませんでした。");
			return res;
		}

		// ---- ① 見出し（作品名）と、主題歌の曲名 ----
		List<Double> hs = new ArrayList<>();
		for (L l : ls) {
			hs.add(l.h - Math.abs(slope) * l.w);
		}
		double medianH = median(hs);
		L title = null;
		int limit = Math.min(ls.size(), 6);
		for (int i = 0; i < limit; i++) {
			L l = ls.get(i);
			double hh = l.h - Math.abs(slope) * l.w;
			if (l.len >= 3 && hh >= medianH * 1.1 && (title == null || hh > title.h - Math.abs(slope) * title.w)) {
				title = l;
			}
		}
		if (title != null) {
			res.workTitleGuess = title.t;
		}

		int startIdx = title == null ? 0 : ls.indexOf(title) + 1;
		List<L> songs = new ArrayList<>();
		double firstLeftCorr = 0;
		for (int i = startIdx; i < ls.size(); i++) {
			L l = ls.get(i);
			boolean ok = l.len >= 3 && !isDiscoLike(l) && l.w <= 900;
			if (songs.isEmpty()) {
				if (!ok) {
					if (title != null && l.yRef - ls.get(startIdx - 1).yRef > 150) {
						break;
					}
					continue;
				}
				firstLeftCorr = l.left - dxdy * l.cy;
				songs.add(l);
			} else {
				L prev = songs.get(songs.size() - 1);
				if (ok && Math.abs((l.left - dxdy * l.cy) - firstLeftCorr) <= 300 && l.yRef - prev.yRef <= 75) {
					songs.add(l);
				} else {
					break;
				}
			}
		}
		if (songs.isEmpty()) {
			res.messages.add("主題歌の曲名の並びを見つけられませんでした。");
			return res;
		}

		for (int i = 0; i < songs.size(); i++) {
			SongRow s = new SongRow();
			String rawTitle = songs.get(i).t;
			String cleanTitle = cleanSongTitle(rawTitle);
			s.title = cleanTitle;
			if (!cleanTitle.equals(rawTitle)) {
				s.memo = "先頭の記号「" + rawTitle.substring(0, rawTitle.length() - cleanTitle.length()) + "」は、チェック欄・番号として取り除きました";
			}
			s.source = new Rectangle(songs.get(i).src.rect);
			// 区分（OP/ED等）は、左端の記号が影で読めないことが多いので、一般的な並びから推定する
			s.kubun = i == 0 ? "OP" : i == 1 ? "ED" : "挿入歌";
			if (songs.get(i).src.confidence >= 0 && songs.get(i).src.confidence < 0.7) {
				s.memo = "曲名のOCR信頼度が低い行です";
			}
			res.songs.add(s);
		}
		res.messages.add("区分（OP・ED・挿入歌）は、左端の記号が読めないため推定です（1曲目=OP、2曲目=ED、以降=挿入歌）。画像と見比べて直してください。");
		int n = songs.size();
		double lastSongY = songs.get(n - 1).yRef;

		// ---- ② 表（作詞・作曲・編曲・歌手）----
		double firstDiscoY = Double.MAX_VALUE;
		for (L l : ls) {
			if (l.yRef > lastSongY + 5 && isDiscoLike(l)) {
				firstDiscoY = Math.min(firstDiscoY, l.yRef);
			}
		}
		List<L> cells = new ArrayList<>();
		for (L l : ls) {
			if (l.yRef > lastSongY + 5 && l.yRef < firstDiscoY - 5 && l.len <= 10 && l.w <= 420 && !isDiscoLike(l)
					&& !songs.contains(l)) {
				cells.add(l);
			}
		}
		fillTable(res, cells, n);

		// ---- ③ 音源 ----
		List<L> zone = new ArrayList<>();
		double prevY = -1;
		for (L l : ls) {
			if (l.yRef <= lastSongY + 5 || cells.contains(l) || songs.contains(l) || l == title) {
				continue;
			}
			if (isOnlyDigits(l)) {
				continue; // ページ番号など
			}
			if (prevY >= 0 && l.yRef - prevY > ZONE_GAP) {
				break; // 間があいたら、そこから先は解説文や別の作品
			}
			zone.add(l);
			prevY = l.yRef;
		}
		int used = songs.size() + cells.size() + zone.size() + (title == null ? 0 : 1);
		buildCds(res, zone, n, res.pageYear);
		if (region == null && ls.size() - used >= 5) {
			res.messages.add("使われなかった行が" + (ls.size() - used)
					+ "行あります。このページに別の作品ものっている場合は、その作品の範囲を画像で選んで「選んだ範囲から候補を作る」を押してください。");
		}
		return res;
	}

	// ==================== 表 ====================

	/** 表のセルの行を、列に分け、曲の行に対応づけて、曲の候補に書き込む。 */
	private static void fillTable(Result res, List<L> cells, int n) {
		// 列に分ける：左右の位置が大きく離れたところで区切る
		List<L> byX = new ArrayList<>(cells);
		byX.sort(Comparator.comparingDouble(l -> l.cx));
		List<List<L>> clusters = new ArrayList<>();
		for (L l : byX) {
			if (clusters.isEmpty() || l.cx - clusters.get(clusters.size() - 1).get(clusters.get(clusters.size() - 1).size() - 1).cx > 110) {
				clusters.add(new ArrayList<>());
			}
			clusters.get(clusters.size() - 1).add(l);
		}

		// 各列を上から並べ、最初の「名前らしい」行より上（見出しの読み残し）を取り除く。名前が1つも無い列は使わない
		List<List<L>> cols = new ArrayList<>();
		for (List<L> c : clusters) {
			c.sort(Comparator.comparingDouble(l -> l.yRef));
			int first = -1;
			for (int i = 0; i < c.size(); i++) {
				if (isNameLike(c.get(i).t)) {
					first = i;
					break;
				}
			}
			if (first >= 0) {
				cols.add(new ArrayList<>(c.subList(first, c.size())));
			}
		}
		if (cols.size() < 3) {
			res.messages.add("表の列（作曲・編曲・歌手）を見つけられませんでした。作詞者・作曲者・編曲者・歌唱者は手で入力してください。");
			return;
		}
		// 右から順に、歌手・編曲・作曲・（あれば）作詞
		List<L> singerCol = cols.get(cols.size() - 1);
		List<L> arrangerCol = cols.get(cols.size() - 2);
		List<L> composerCol = cols.get(cols.size() - 3);
		List<L> lyricistCol = cols.size() >= 4 ? cols.get(cols.size() - 4) : null;

		// 表の行の位置（アンカー）：曲の数とちょうど同じ数の項目がある列があれば、それを基準にする
		List<L> anchorCol = null;
		for (List<L> c : List.of(composerCol, arrangerCol, singerCol)) {
			if (c.size() == n) {
				anchorCol = c;
				break;
			}
		}
		if (anchorCol != null) {
			double[] bounds = new double[n - 1];
			for (int i = 0; i < n - 1; i++) {
				bounds[i] = (anchorCol.get(i).yRef + anchorCol.get(i + 1).yRef) / 2;
			}
			String[] composer = fillColumn(composerCol, bounds, n, true);
			String[] arranger = fillColumn(arrangerCol, bounds, n, true);
			String[] singer = fillColumn(singerCol, bounds, n, false);
			String[] lyricist = lyricistCol == null ? new String[n] : fillColumn(lyricistCol, bounds, n, true);
			for (int i = 0; i < n; i++) {
				SongRow s = res.songs.get(i);
				s.composer = nz(composer[i]);
				s.arranger = nz(arranger[i]);
				s.singer = nz(singer[i]);
				s.lyricist = nz(lyricist[i]);
			}
			if (lyricistCol == null) {
				res.messages.add("作詞者の列は読み取れませんでした（写真の左側が暗いことが多い部分です）。作詞者は手で入力してください。");
			}
			res.messages.add("表は、曲の数（" + n + "行）に合う列を基準に、傾きを直して行に対応づけました。「〃」は、上の行と同じとして補っています。");
			return;
		}

		// ちょうど合う列が無い場合：曲が1件で、表の項目も1件だけのときに限り、その1件をその曲に当てはめる
		// （それ以外は、どの行に当てはめてよいか自信を持って決められないため、空のままにする）
		if (n == 1) {
			boolean any = false;
			if (composerCol.size() == 1) {
				res.songs.get(0).composer = composerCol.get(0).t;
				any = true;
			}
			if (arrangerCol.size() == 1) {
				res.songs.get(0).arranger = arrangerCol.get(0).t;
				any = true;
			}
			if (singerCol.size() == 1) {
				res.songs.get(0).singer = singerCol.get(0).t;
				any = true;
			}
			if (lyricistCol != null && lyricistCol.size() == 1) {
				res.songs.get(0).lyricist = lyricistCol.get(0).t;
				any = true;
			}
			if (any) {
				res.messages.add("曲が1件だけなので、見つかった表の項目をそのまま当てはめました。画像と見比べて確認してください。");
				return;
			}
		}
		res.messages.add("表の行と曲の対応を決められませんでした（曲" + n + "件に対して、表の項目数が合いません）。作詞者・作曲者・編曲者・歌唱者は手で入力してください。");
	}

	/**
	 * 1つの列の項目を、表の行（0〜n-1）に振り分ける。「〃」や空欄は、上の行と同じにする（歌手は空欄のまま残す場合あり）。
	 * 列の項目数が曲の数より少ないとき（OCRで一部しか読み取れなかったとき）は、読み取れた項目だけを、
	 * 一番近い曲の行に当てはめる（緩やかな一致）。列が無い（null）ときは、全部空のまま返す。
	 */
	private static String[] fillColumn(List<L> col, double[] bounds, int n, boolean fillDown) {
		String[] result = new String[n];
		if (col == null || col.isEmpty()) {
			return result;
		}
		List<List<String>> names = new ArrayList<>();
		boolean[] hasMark = new boolean[n];
		for (int i = 0; i < n; i++) {
			names.add(new ArrayList<>());
		}
		for (L l : col) {
			int row = 0;
			while (row < bounds.length && l.yRef > bounds[row]) {
				row++;
			}
			if (isNameLike(l.t)) {
				names.get(row).add(l.t);
			} else {
				hasMark[row] = true;
			}
		}
		for (int i = 0; i < n; i++) {
			if (!names.get(i).isEmpty()) {
				result[i] = String.join("／", names.get(i));
			} else if (i > 0 && (hasMark[i] || fillDown)) {
				result[i] = result[i - 1]; // 〃（上と同じ）
			}
		}
		return result;
	}

	// ==================== 音源 ====================

	private static class Entry {
		String head = "";
		String title = "";
		final List<String> notes = new ArrayList<>();
		Rectangle rect;
		double minConf = 1;
	}

	/** 音源の一覧の行を、1件ずつに区切って、音源の候補を作る。 */
	private static void buildCds(Result res, List<L> zone, int songCount, int pageYear) {
		if (zone.isEmpty()) {
			res.messages.add("音源（レコード・CD）の行は見つかりませんでした。");
			return;
		}
		// 同じ高さにある断片（例：左の品番と、右の日付）を、1行にまとめる
		List<List<L>> rows = new ArrayList<>();
		for (L l : zone) {
			if (!rows.isEmpty()) {
				List<L> last = rows.get(rows.size() - 1);
				if (Math.abs(l.yRef - last.get(0).yRef) <= ROW_MERGE) {
					last.add(l);
					continue;
				}
			}
			List<L> r = new ArrayList<>();
			r.add(l);
			rows.add(r);
		}

		List<Entry> entries = new ArrayList<>();
		Entry cur = null;
		for (List<L> r : rows) {
			r.sort(Comparator.comparingDouble(l -> l.cx));
			StringBuilder sb = new StringBuilder();
			Rectangle rect = null;
			double conf = 1;
			for (L l : r) {
				sb.append(sb.length() > 0 ? " " : "").append(l.t);
				rect = rect == null ? new Rectangle(l.src.rect) : rect.union(l.src.rect);
				if (l.src.confidence >= 0) {
					conf = Math.min(conf, l.src.confidence);
				}
			}
			String text = sb.toString();
			boolean isHead = DATE_PAREN.matcher(text).find() || findCode(text) != null;
			boolean isTitle = text.startsWith("●") || text.contains("セット絵本");

			if (isHead) {
				cur = new Entry();
				entries.add(cur);
				cur.head = text;
			} else if (isTitle) {
				if (cur == null || !cur.title.isEmpty() || !cur.notes.isEmpty()) {
					cur = new Entry();
					entries.add(cur);
				}
				cur.title = text;
			} else {
				if (cur == null) {
					cur = new Entry();
					entries.add(cur);
				}
				cur.notes.add(text);
			}
			cur.rect = cur.rect == null ? rect : cur.rect.union(rect);
			cur.minConf = Math.min(cur.minConf, conf);
		}

		for (Entry e : entries) {
			res.cds.add(toCdRow(e, res, songCount, pageYear));
		}
		res.messages.add("音源は、品番や日付の行をきっかけに区切りました。左端（形式・レーベル）が読めていない行が多いため、品番・発売会社・日付は画像と見比べて確認してください。");
	}

	private static CdRow toCdRow(Entry e, Result res, int songCount, int pageYear) {
		CdRow cd = new CdRow();
		List<String> memo = new ArrayList<>();
		cd.source = e.rect;
		String head = e.head;

		// 品番
		String code = head.isEmpty() ? null : findCode(head);
		if (code != null) {
			cd.hinban = code;
			// 発売会社：品番の前に、大文字＋小文字（Co, Kg, Ap…）などの2文字がある場合だけ
			Matcher cm = Pattern.compile("(?<![A-Za-z])(AS|[A-Z][a-z])\\s+" + Pattern.quote(code)).matcher(head);
			if (cm.find()) {
				cd.company = cm.group(1);
			}
		} else if (!head.isEmpty()) {
			memo.add("品番が読み取れていません");
		}

		// 発売日（例：(74. 5) → 1974.5発売）
		List<String> noteParts = new ArrayList<>();
		String afterDate = head;
		Matcher dm = DATE_PAREN.matcher(head);
		if (dm.find()) {
			afterDate = head.substring(dm.end());
			if (dm.group(1) != null) {
				int yy = Integer.parseInt(dm.group(1));
				int year = yy >= 100 ? yy : (yy >= 50 ? 1900 + yy : 2000 + yy);
				noteParts.add(year + (dm.group(2) != null ? "." + Integer.parseInt(dm.group(2)) : "") + "発売");
			}
		} else if (code != null) {
			afterDate = head.substring(head.indexOf(code) + code.length());
		}

		// 収録曲（曲の番号 → 曲名に直す）
		Matcher tm = TRACKS.matcher(afterDate.trim());
		if (tm.find()) {
			List<Integer> nums = expandNumbers(tm.group(1));
			List<String> titles = new ArrayList<>();
			boolean ok = !nums.isEmpty();
			for (int num : nums) {
				if (num >= 1 && num <= res.songs.size()) {
					titles.add(res.songs.get(num - 1).title);
				} else {
					ok = false;
				}
			}
			if (ok) {
				cd.tracks = String.join("、", titles);
			} else {
				cd.tracks = tm.group(1);
				memo.add("収録曲の番号（" + tm.group(1) + "）を、曲名に直せませんでした");
			}
		}

		// タイトル
		if (!e.title.isEmpty()) {
			String t = e.title.replaceFirst("^●", "");
			// 「カセット絵本」の「カ」が別の文字に読み違えられることが多い
			t = t.replaceFirst("^.?セット絵本", "カセット絵本");
			cd.title = t.trim();
		} else {
			cd.title = "初盤";
			memo.add("タイトルの行が無いため「初盤」としました");
		}

		// 備考：発売日＋メモ行
		noteParts.addAll(e.notes);
		cd.note = String.join(" ／ ", noteParts);

		if (e.head.isEmpty()) {
			memo.add("この音源の先頭の行（形式・レーベル・品番・日付）が読み取れていません");
		}
		if (!e.notes.isEmpty()) {
			memo.add("備考の先頭の文字は、「※」の読み違いを含むことがあります");
		}
		if (e.minConf < 0.7) {
			memo.add("OCRの信頼度が低い行を含みます");
		}
		cd.memo = String.join(" / ", memo);
		return cd;
	}

	/** 行から、品番らしい文字列を探す（英字を含むものを優先）。 */
	private static String findCode(String text) {
		String cleaned = DATE_PAREN.matcher(text).replaceAll(" ");
		Matcher m = CODE_LETTERS.matcher(cleaned);
		String best = null;
		while (m.find()) {
			String c = m.group();
			if (c.matches(".*[A-Za-z].*\\d.*") && (best == null || c.length() > best.length())) {
				best = c;
			}
		}
		if (best != null) {
			return best;
		}
		Matcher d = CODE_DIGITS.matcher(cleaned);
		return d.find() ? d.group() : null;
	}

	/** "1~4" → [1,2,3,4]、"1,2" → [1,2]、"2" → [2] */
	private static List<Integer> expandNumbers(String s) {
		List<Integer> list = new ArrayList<>();
		try {
			Matcher r = Pattern.compile("(\\d+)\\s*[~〜～]\\s*(\\d+)").matcher(s);
			if (r.matches()) {
				int a = Integer.parseInt(r.group(1)), b = Integer.parseInt(r.group(2));
				for (int i = a; i <= b && i - a < 50; i++) {
					list.add(i);
				}
				return list;
			}
			for (String p : s.split("[,，]")) {
				list.add(Integer.parseInt(p.trim()));
			}
		} catch (NumberFormatException e) {
			list.clear();
		}
		return list;
	}

	// ==================== 小さな部品たち ====================

	/**
	 * 写真の傾きを推定する。曲名が並んだ行（左端がそろっていて、間隔がほぼ一定の3行以上）を探し、
	 * 下へ行くほど左端がどれだけ右へずれるか（dx/dy）を求める。見つからなければ0。
	 */
	private static double estimateDrift(List<L> all) {
		List<L> sorted = new ArrayList<>(all);
		sorted.sort(Comparator.comparingDouble(l -> l.cy));
		List<L> bestRun = new ArrayList<>();
		List<L> run = new ArrayList<>();
		for (L l : sorted) {
			boolean fits = l.len >= 3 && l.w <= 500;
			if (!fits) {
				continue;
			}
			if (!run.isEmpty()) {
				L p = run.get(run.size() - 1);
				double dy = l.cy - p.cy;
				if (dy >= 20 && dy <= 60 && Math.abs(l.left - p.left) <= 14) {
					run.add(l);
				} else {
					run = new ArrayList<>();
					run.add(l);
				}
			} else {
				run.add(l);
			}
			if (run.size() > bestRun.size()) {
				bestRun = new ArrayList<>(run);
			}
		}
		if (bestRun.size() < 3) {
			return 0;
		}
		// 最小二乗法：左端(x)を、高さ(y)の一次式で近似したときの傾き
		double my = 0, mx = 0;
		for (L l : bestRun) {
			my += l.cy;
			mx += l.left;
		}
		my /= bestRun.size();
		mx /= bestRun.size();
		double num = 0, den = 0;
		for (L l : bestRun) {
			num += (l.cy - my) * (l.left - mx);
			den += (l.cy - my) * (l.cy - my);
		}
		return den == 0 ? 0 : num / den;
	}

	/** 「【74-1】」のような目印から年を推定する。無ければ、年の手がかり（例："1974"）を使う。 */
	private static int findPageYear(List<OcrJson.Line> lines, String yearHint) {
		if (yearHint != null && yearHint.trim().matches("\\d{4}")) {
			return Integer.parseInt(yearHint.trim());
		}
		Pattern p = Pattern.compile("【\\s*(\\d{2})\\s*-\\s*\\d+\\s*】");
		for (OcrJson.Line l : lines) {
			Matcher m = p.matcher(l.text);
			if (m.find()) {
				int yy = Integer.parseInt(m.group(1));
				return yy >= 50 ? 1900 + yy : 2000 + yy;
			}
		}
		return -1;
	}

	private static boolean isDiscoLike(L l) {
		return DISCO_MARK.matcher(l.t).find();
	}

	private static boolean isOnlyDigits(L l) {
		return l.t.replaceAll("[\\s\\d.,・ー-]", "").isEmpty() && l.len <= 4;
	}

	/**
	 * 曲名の先頭に残った、チェック欄・番号・区分の記号（例："□2E"）を取り除く。
	 * 数字やチェック欄の記号が実際に含まれているときだけ取り除き、本当の曲名を誤って削ってしまわないようにする。
	 */
	static String cleanSongTitle(String t) {
		Matcher m = Pattern.compile("^[□☐〽\\s0-9OEoe.]{0,6}").matcher(t);
		if (m.find()) {
			String prefix = m.group();
			String rest = t.substring(prefix.length());
			if (!rest.isEmpty() && prefix.matches(".*[0-9□☐〽].*")) {
				return rest;
			}
		}
		return t;
	}

	/** 人名・曲名らしい文字列か（漢字・かな・カタカナが3文字以上で、大半を占める）。 */
	static boolean isNameLike(String t) {
		int cjk = 0;
		int total = 0;
		for (char c : t.toCharArray()) {
			if (Character.isWhitespace(c)) {
				continue;
			}
			total++;
			Character.UnicodeScript sc = Character.UnicodeScript.of(c);
			if (sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
					|| sc == Character.UnicodeScript.KATAKANA || c == 'ー' || c == '・') {
				cjk++;
			}
		}
		return cjk >= 3 && cjk >= total * 0.6;
	}

	private static double median(List<Double> values) {
		List<Double> v = new ArrayList<>(values);
		v.sort(null);
		return v.isEmpty() ? 0 : v.get(v.size() / 2);
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	/**
	 * OCRの文字の中に、作品名にとても近い（1文字違い）文字列があれば、正しい作品名に直す。
	 * 例：作品名が「アルプスの少女ハイジ」のとき、「アルブスの少女ハイジ1」→「アルプスの少女ハイジ1」。
	 */
	public static String fixTitleTypos(String text, String workTitle) {
		if (workTitle == null || workTitle.length() < 4 || text == null) {
			return text;
		}
		int n = workTitle.length();
		StringBuilder sb = new StringBuilder(text);
		for (int i = 0; i + n <= sb.length(); i++) {
			String window = sb.substring(i, i + n);
			int diff = 0;
			for (int k = 0; k < n && diff <= 1; k++) {
				if (window.charAt(k) != workTitle.charAt(k)) {
					diff++;
				}
			}
			if (diff == 1) {
				sb.replace(i, i + n, workTitle);
				i += n - 1;
			}
		}
		return sb.toString();
	}
}
