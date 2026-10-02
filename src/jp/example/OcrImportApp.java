package jp.example;

import java.awt.Image;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class OcrImportApp {

    static File selectedImageFile; // 選んだ画像を、後の処理でも使えるように覚えておく箱

    public static void main(String[] args) {

        JFrame frame = new JFrame("資料の取り込み（OCR）");
        frame.setSize(700, 700);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        JButton chooseButton = new JButton("画像を選ぶ");
        JLabel imageLabel = new JLabel();

        mainPanel.add(chooseButton);
        mainPanel.add(imageLabel);
        
        JButton ocrButton = new JButton("文字を読み取る");
        JTextArea resultArea = new JTextArea(15, 40);
        JScrollPane resultScroll = new JScrollPane(resultArea);

        mainPanel.add(ocrButton);
        mainPanel.add(resultScroll);

        chooseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            int result = chooser.showOpenDialog(frame);
            if (result == JFileChooser.APPROVE_OPTION) {
                selectedImageFile = chooser.getSelectedFile();

                ImageIcon icon = new ImageIcon(selectedImageFile.getPath());
                Image scaled = icon.getImage().getScaledInstance(600, -1, Image.SCALE_SMOOTH);
                imageLabel.setIcon(new ImageIcon(scaled));
            }
        });
        
        ocrButton.addActionListener(e -> {
            if (selectedImageFile == null) {
                resultArea.setText("先に画像を選んでください");
                return;
            }

            try {
                //String tesseractPath = "C:\\Program Files\\Tesseract-OCR\\tesseract.exe";
            	String tesseractPath = "C:\\Users\\user12\\AppData\\Local\\Programs\\Tesseract-OCR\\tesseract.exe";
                String outputBase = "ocr_result"; // 拡張子なし。Tesseractが自動で .txt をつける

                ProcessBuilder pb = new ProcessBuilder(
                    tesseractPath, selectedImageFile.getPath(), outputBase, "-l", "jpn"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.waitFor(); // 読み取りが終わるまで待つ

                String text = Files.readString(Paths.get(outputBase + ".txt"));
                resultArea.setText(text);

            } catch (Exception ex) {
                resultArea.setText("エラー：" + ex.getMessage());
            }
        });

        frame.add(new JScrollPane(mainPanel));
        
        frame.setVisible(true);
    }
}