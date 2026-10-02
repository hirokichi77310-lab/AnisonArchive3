package jp.example;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCRで読み取ったテキスト（例：1974年の目次ページ）を、
 * 「作品名・放映開始日・放映終了日」の候補に分解する部品。
 *
 * 考え方：
 * ・OCRの間違いのうち、ルールで直せるもの（「,」と「.」の取り違え、日付の後ろにくっついたページ番号など）だけを自動で直す。
 * ・直した場所や怪しい場所には、必ず「注意メモ」を付ける（黙って直さない）。
 * ・別のOCR結果（Googleドキュメントなど）を渡すと、食い違いも注意メモに追加する。
 * 最終的な判断は、人が元の画像と見比べて行う。
 */
public class OcrLineParser {

	/** 表の1行（＝1作品）分の候補。 */
	public static class Row {
		public boolean include; // 登録する対象にするか（最初の状態）
		public String kind = "TV"; // "TV" または "映画"
		public String title = "";
		public String start = ""; // yyyy/MM/dd（読み取れなければ空）
		public String end = ""; // yyyy/MM/dd（読み取れなければ空、または途中まで）
		public String status = "OK"; // "OK" / "要確認" / "日付要入力"
		public String note = ""; // 注意メモ
		public String raw = ""; // 元のOCRの1行
	}

	/** 解析結果全体。 */
	public static class Result {
		public String year = "";
		public final List<Row> rows = new ArrayList<>();
		public final List<String> skipped = new ArrayList<>(); // 作品の行として読めなかった行
	}

	/** 「作品名 ……… 日付」の形（点線＝リーダー線が3つ以上続く）の行を見つけるための型。 */
	private static final Pattern ENTRY = Pattern.compile("^(.*?)[…‥・.]{3,}(.*)$");

	/**
	 * @param text      OCR結果のテキスト全体
	 * @param yearText  資料の年（空なら、テキスト中の「1974」のような行から探す）
	 * @param checkText 照合用の別OCR結果（無ければnullか空文字）
	 */
	public static Result parse(String text, String yearText, String checkText) {
		Result result = new Result();
		String[] lines = text.split("\\R");

		// --- 年を決める（開始日には年が書かれていないため、資料の見出しの年を使う） ---
		int year = -1;
		if (yearText != null && toHalf(yearText.trim()).matches("\\d{4}")) {
			year = Integer.parseInt(toHalf(yearText.trim()));
		} else {
			for (String l : lines) {
				String t = toHalf(l).replaceAll("\\s", "");
				if (t.matches("\\d{4}")) {
					year = Integer.parseInt(t);
					break;
				}
			}
		}
		result.year = year < 0 ? "" : String.valueOf(year);
		if (year < 0) {
			return result; // 年が分からないと開始日を決められない
		}

		String checkNorm = (checkText == null || checkText.isBlank()) ? null : normalizeForCompare(checkText);

		// --- 1行ずつ読む ---
		String kind = "TV";
		for (String rawLine : lines) {
			String line = rawLine.trim();
			if (line.isEmpty()) {
				continue;
			}

			// 【TV】【映画】のような見出し行：種別を切り替える（OCRで【IV】になっても、映画以外はTV扱い）
			if (line.startsWith("【")) {
				kind = line.contains("映画") ? "映画" : "TV";
				continue;
			}

			Matcher m = ENTRY.matcher(line);
			if (!m.matches()) {
				if (!toHalf(line).replaceAll("\\s", "").matches("\\d{4}")) {
					result.skipped.add(line);
				}
				continue;
			}
			result.rows.add(parseEntry(line, m.group(1), m.group(2), kind, year, checkNorm));
		}
		return result;
	}

	/** 1行分（作品名の部分＋日付の部分）を分解する。 */
	private static Row parseEntry(String line, String titlePart, String datePartRaw, String kind, int year,
			String checkNorm) {
		Row row = new Row();
		row.raw = line;
		row.kind = kind;
		List<String> notes = new ArrayList<>();

		// ---------- 作品名 ----------
		// 先頭の「□」（チェック欄）は、OCRで「〽」や「1」に化けることがあるので取り除く
		String title = titlePart.trim().replaceFirst("^[□☐〽\\s]+", "");
		if (title.matches("^1[^\\d\\s].*")) {
			title = title.substring(1);
			notes.add("先頭の「1」は□の読み違いと判断して取り除きました");
		}
		row.title = title.trim();

		// ---------- 日付 ----------
		// 「,」「、」を「.」に直し、空白を消し、「〜」「~」を「～」にそろえる
		String d = toHalf(datePartRaw).replaceAll("[,，、。]", ".").replaceAll("[\\s　]", "").replace("〜", "～")
				.replace("~", "～");
		String[] parts = d.split("～", -1);

		String startPart = parts[0];
		String endPart = parts.length >= 2 ? parts[1] : "";
		if (kind.equals("TV") && parts.length < 2) {
			notes.add("「～」が読み取れません（OCR：" + d + "）");
		}

		// 開始日（月.日）：年は資料の見出しの年を使う
		Matcher sm = Pattern.compile("^(\\d{1,2})\\.(\\d+)(.*)$").matcher(startPart);
		if (sm.matches()) {
			int month = Integer.parseInt(sm.group(1));
			String digits = sm.group(2);
			int len = chooseDayLength(year, month, digits);
			if (len > 0) {
				int day = Integer.parseInt(digits.substring(0, len));
				row.start = fmt(year, month, day);
				String left = digits.substring(len) + sm.group(3);
				if (!left.isEmpty()) {
					notes.add("開始日の後ろの余分な文字「" + left + "」を取り除きました");
				}
			} else {
				notes.add("開始日が読み取れません（OCR：" + startPart + "）");
			}
		} else if (!startPart.isEmpty()) {
			notes.add("開始日が読み取れません（OCR：" + startPart + "）");
		}

		// 終了日（年.月.日）：日の後ろに、ページ番号などの数字がくっつくことがある
		if (kind.equals("TV") && !endPart.isEmpty()) {
			Matcher em = Pattern.compile("^(\\d{4})\\.(\\d{1,2})\\.(\\d+)(.*)$").matcher(endPart);
			if (em.matches()) {
				int endYear = Integer.parseInt(em.group(1));
				int month = Integer.parseInt(em.group(2));
				String digits = em.group(3);
				int len = chooseDayLength(endYear, month, digits);
				if (len > 0) {
					int day = Integer.parseInt(digits.substring(0, len));
					row.end = fmt(endYear, month, day);
					String left = digits.substring(len) + em.group(4);
					if (!left.isEmpty()) {
						notes.add("終了日の後ろの余分な文字「" + left + "」を取り除きました（ページ番号などの混入）");
					}
				} else {
					notes.add("終了日が読み取れません（OCR：" + endPart + "）");
				}
			} else {
				// 「年.月.」までは読めているが、日が読めない場合は、途中まで入れて確認を促す
				Matcher pm = Pattern.compile("^(\\d{4})\\.(\\d{1,2})\\..*$").matcher(endPart);
				if (pm.matches()) {
					row.end = String.format("%04d/%02d/", Integer.parseInt(pm.group(1)), Integer.parseInt(pm.group(2)));
					notes.add("終了日の「日」が読み取れません（OCR：" + endPart + "）。元の画像を見て入力してください");
				} else {
					notes.add("終了日が読み取れません（OCR：" + endPart + "）");
				}
			}
		}

		// 開始日が終了日より後になっていないか
		if (isDate(row.start) && isDate(row.end) && toDate(row.start).isAfter(toDate(row.end))) {
			notes.add("開始日が終了日より後になっています");
		}

		// ---------- 照合用OCRとの突き合わせ ----------
		if (checkNorm != null) {
			if (!row.title.isEmpty() && !checkNorm.contains(normalizeForCompare(row.title))) {
				notes.add("作品名が照合用OCRと食い違います");
			}
			if (kind.equals("TV") && isDate(row.end)) {
				LocalDate e = toDate(row.end);
				String key = e.getYear() + "." + e.getMonthValue() + "." + e.getDayOfMonth();
				if (!checkNorm.contains(key)) {
					notes.add("終了日が照合用OCRに見つかりません");
				}
			}
		}

		// ---------- 状態を決める ----------
		boolean startOk = isDate(row.start);
		boolean endOk = kind.equals("TV") ? isDate(row.end) : true; // 映画は終了日が無いのが普通
		if (!startOk || !endOk) {
			row.status = "日付要入力";
		} else if (!notes.isEmpty()) {
			row.status = "要確認";
		} else {
			row.status = "OK";
		}
		row.note = String.join(" / ", notes);
		// 最初に登録対象にするのは、TVで、日付がそろっている行だけ
		row.include = kind.equals("TV") && !row.status.equals("日付要入力");
		return row;
	}

	// ==================== 小さな部品たち ====================

	/**
	 * 日の数字の並び（例："8101"）から、日付として正しくなる一番長い先頭（最大2桁）の長さを返す。
	 * "8101"なら、81は日として成り立たないので1桁の"8"（長さ1）。"2710"なら"27"（長さ2）。
	 * 成り立つものがなければ0。
	 */
	private static int chooseDayLength(int year, int month, String digits) {
		for (int len = Math.min(2, digits.length()); len >= 1; len--) {
			int day = Integer.parseInt(digits.substring(0, len));
			if (isValidDate(year, month, day)) {
				return len;
			}
		}
		return 0;
	}

	private static boolean isValidDate(int year, int month, int day) {
		if (month < 1 || month > 12 || day < 1) {
			return false;
		}
		return day <= YearMonth.of(year, month).lengthOfMonth();
	}

	private static String fmt(int year, int month, int day) {
		return String.format("%04d/%02d/%02d", year, month, day);
	}

	private static boolean isDate(String s) {
		return s != null && s.matches("^\\d{4}/\\d{2}/\\d{2}$");
	}

	private static LocalDate toDate(String s) {
		return LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)),
				Integer.parseInt(s.substring(8, 10)));
	}

	/** 全角数字（０-９）を半角数字（0-9）に変える。 */
	static String toHalf(String s) {
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c >= '０' && c <= '９') {
				sb.append((char) (c - '０' + '0'));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** 照合用に、数字を半角にそろえ、空白・改行をすべて取り除く。 */
	static String normalizeForCompare(String s) {
		return toHalf(s).replaceAll("[\\s　]+", "");
	}
}
