package com.uddernetworks.mspaint.gui.window.search;

import com.uddernetworks.mspaint.main.LetterFileWriter;
import com.uddernetworks.mspaint.main.MainGUI;
import com.uddernetworks.mspaint.texteditor.LetterGenerator;
import com.uddernetworks.newocr.character.ImageLetter;
import com.uddernetworks.newocr.recognition.ScannedImage;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ReplaceManager {

    private static Logger LOGGER = LoggerFactory.getLogger(ReplaceManager.class);

    private MainGUI mainGUI;

    public ReplaceManager(MainGUI mainGUI) {
        this.mainGUI = mainGUI;
    }

    public void replaceText(SearchResult searchResult, String text) throws IOException, ExecutionException, InterruptedException {
        var ocrManager = this.mainGUI.getMain().getOCRManager();
        var actions = ocrManager.getActions();
        ScannedImage scannedImage = searchResult.getScannedImage();

        var descriptiveStatistics = new DescriptiveStatistics();

        var sizes = scannedImage
                .getGrid()
                .values()
                .stream()
                .flatMap(List::stream)
                .filter(imageLetter -> imageLetter.getLetter() != ' ')
                .map(actions::getFontSize)
                .filter(OptionalDouble::isPresent)
                .map(OptionalDouble::getAsDouble)
                .peek(descriptiveStatistics::addValue)
                .collect(Collectors.toCollection(DoubleArrayList::new));

        var lowerBound = descriptiveStatistics.getPercentile(20);
        var upperBound = descriptiveStatistics.getPercentile(80);

        System.out.println("sizes = " + sizes);

        sizes.removeIf((Predicate<Double>) value -> value > upperBound || value < lowerBound);

        System.out.println("sizes = " + sizes);

        var size = sizes.stream().mapToDouble(Double::valueOf).average().orElse(0D);

        LetterGenerator letterGenerator = new LetterGenerator();
        var spaceOptional = this.mainGUI.getMain().getOCRManager().getActiveFont().getDatabaseManager().getAllCharacterSegments().get().stream().filter(databaseCharacter -> databaseCharacter.getLetter() == ' ').findFirst();

        if (spaceOptional.isEmpty()) {
            LOGGER.error("Couldn't find space for size: " + size);
            return;
        }

        var space = spaceOptional.get();

        double spaceRatio = space.getAvgWidth() / space.getAvgHeight();
        int characterBetweenSpace = (int) ((spaceRatio * size) / 3);

        var centerPopulator = this.mainGUI.getMain().getCenterPopulator();
        centerPopulator.generateCenters((int) size);

        int foundPos = searchResult.getFoundPosition();
        Map.Entry<Integer, List<ImageLetter>> lineEntry = scannedImage.getLineEntry(searchResult.getLineNumber() - 1);
        List<ImageLetter> line = lineEntry.getValue();
        int lineY = lineEntry.getKey();

        final int imageLettersSize = searchResult.getImageLetters().size();

        int x = line.get(foundPos).getX();

        int farRight = line.get(foundPos + imageLettersSize).getX() - x;

        for (int i = 0; i < imageLettersSize; i++) {
            line.remove(foundPos);
        }

        int addBy = 0;

        line.forEach(imageLetter -> imageLetter.setX(imageLetter.getX() - farRight));

        List<ImageLetter> adding = new ArrayList<>();

        for (int i = 0; i < text.toCharArray().length; i++) {
            x += addBy;
            char cha = text.charAt(i);

            if (cha == ' ') {
                addBy = (int) Math.floor(spaceRatio * size) - characterBetweenSpace;
            } else {
                boolean[][] letterGrid = letterGenerator.generateCharacter(cha, (int) size, ocrManager.getActiveFont(), space);
                int center = centerPopulator.getCenter(cha, (int) size);

                ImageLetter letter = new ImageLetter(cha, 0, x, lineY - center - (int) size + (int) size, letterGrid[0].length, letterGrid.length - 1, 0D, 0D, 0D);
                letter.setValues(letterGrid);
                letter.setData(Color.BLACK);
                adding.add(letter);

                addBy = letterGrid[0].length + characterBetweenSpace;
            }

            int finalAddBy = addBy;
            line.forEach(imageLetter -> imageLetter.setX(imageLetter.getX() + finalAddBy));
        }

        line.addAll(adding);

        BufferedImage original = ImageIO.read(searchResult.getFile());

        BufferedImage bufferedImage = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);

        LetterFileWriter letterFileWriter = new LetterFileWriter(scannedImage, bufferedImage, searchResult.getFile());
        letterFileWriter.writeToFile();
    }
}
