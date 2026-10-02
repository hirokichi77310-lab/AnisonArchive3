package jp.example;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/**
 * 管理者の「ユーザー名・パスワード」を扱う部品。
 *
 * 以前は、ソースコードの中に "admin" / "pw" とそのまま書いていたが、
 * これだと、GitHubなどにソースコードを公開したときに、パスワードまで一緒に見えてしまう。
 *
 * そこで、ユーザー名と、パスワードを元にした「ハッシュ」（元のパスワードには戻せない、別の文字列）を、
 * test.dbと同じフォルダの設定ファイル（admin.properties）に保存するようにした。
 * パスワードそのものは、どこにも保存しない。ログイン時は、入力されたパスワードから同じ方法でハッシュを作り、
 * 保存してあるハッシュと一致するかどうかだけを見る。
 *
 * admin.propertiesは、test.dbと同じ理由（個人の設定なので）で、.gitignoreに入れて公開しないようにする。
 */
public class AdminAuth {

	private static final String FILE_NAME = "admin.properties";

	/** すでに管理者アカウントが設定されているか（初回起動かどうかの目印）。 */
	public static boolean hasAccount() {
		return configFile().isFile();
	}

	/** 新しく（または直し直して）、ユーザー名とパスワードを設定する。 */
	public static void setup(String username, String password) throws IOException {
		String salt = randomSalt();
		Properties p = new Properties();
		p.setProperty("username", username);
		p.setProperty("salt", salt);
		p.setProperty("hash", hash(password, salt));
		try (var out = Files.newOutputStream(configFile().toPath())) {
			p.store(out, "アニ伝アーカイブ 管理者アカウント（このファイルは公開しないでください。パスワードそのものではなく、元に戻せない形のハッシュを保存しています）");
		}
	}

	/** 入力されたユーザー名・パスワードが、設定されているものと一致するか確認する。 */
	public static boolean authenticate(String username, String password) {
		if (!hasAccount()) {
			return false;
		}
		try {
			Properties p = new Properties();
			try (var in = Files.newInputStream(configFile().toPath())) {
				p.load(in);
			}
			String savedUser = p.getProperty("username", "");
			String salt = p.getProperty("salt", "");
			String savedHash = p.getProperty("hash", "");
			return username.equals(savedUser) && hash(password, salt).equals(savedHash);
		} catch (IOException e) {
			return false;
		}
	}

	/** 今設定されているユーザー名（パスワード変更画面で、今のパスワードを確認するために使う）。無ければ空文字。 */
	static String currentUsernameOrEmpty() {
		if (!hasAccount()) {
			return "";
		}
		try {
			Properties p = new Properties();
			try (var in = Files.newInputStream(configFile().toPath())) {
				p.load(in);
			}
			return p.getProperty("username", "");
		} catch (IOException e) {
			return "";
		}
	}

	private static File configFile() {
		return new File(Database.dataFolder(), FILE_NAME);
	}

	/** 毎回ちがう「塩（ソルト）」を作る。同じパスワードでも、毎回ちがうハッシュになるようにするため。 */
	private static String randomSalt() {
		byte[] b = new byte[16];
		new SecureRandom().nextBytes(b);
		return Base64.getEncoder().encodeToString(b);
	}

	/** パスワードと塩から、ハッシュ（元に戻せない文字列）を作る。 */
	private static String hash(String password, String salt) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(salt.getBytes(StandardCharsets.UTF_8));
			byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256はJavaに標準で必ず入っているので、ここには来ない
			throw new RuntimeException(e);
		}
	}
}
