package SpeechToText;

import Arguments.SpeechToTextArguments;
import Search.CacheKey;
import Search.Greppable;

import Search.GreppableTranscript;
import com.google.gson.JsonObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;


public class VoskSpeechToText implements SpeechToText {

    private static final int SAMPLING_RATE = 16000;
    private static final String MODEL_DIRECTORY = "model/vosk-model-LARGE-en-uszz";
    private static final int AUDIO_BYTE_ARRAY_SIZE = 4086;
    private CacheKey cacheKey;

    public VoskSpeechToText() {
        voskSetup();
    }

    private void voskSetup() {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
    }

    @Override
    public Greppable getGreppableResult(CacheKey cacheKey, SpeechToTextArguments arguments) {

        this.cacheKey = cacheKey;
        System.out.println("inside vosk. Timestamp file is " + cacheKey.getTimestampFilename() + " transcript file is " + cacheKey.getTranscriptFilename() + " thread id is " + Thread.currentThread().getId() + " filename to search is " + cacheKey.filename + " object id is " + System.identityHashCode(this));

        String convertedAudioFile = VoskConverter.convertToVoskFormat(cacheKey.filename);
        sendAudioToTimestampedFile(convertedAudioFile);

        return new GreppableTranscript(cacheKey,
                                        arguments.wordsToPrintBeforeMatch,
                                        arguments.wordsToPrintAfterMatch);
    }

    private void sendAudioToTimestampedFile(String audioFileName) {

        InputStream audioInputStream = createInputStream(audioFileName);
        Recognizer recognizer = createRecognizer();

        createOutputFiles();
        printMainRecognizerResults(recognizer, audioInputStream);
        printFinalRecognizerResult(recognizer);
    }

    private InputStream createInputStream(String audioFileName) {

        InputStream AudioInputStream = null;
        try {
            FileInputStream fileInput = getFileInputStream(audioFileName);
            BufferedInputStream bufferedInput = new BufferedInputStream(fileInput);
            AudioInputStream = AudioSystem.getAudioInputStream(bufferedInput);
        } catch (UnsupportedAudioFileException | IOException e ) {
            System.out.println("Audio file not found or audio file unsupported");
        }

        return AudioInputStream;
    }

    private FileInputStream getFileInputStream(String audioFileName) {

        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(audioFileName);
        } catch (java.io.FileNotFoundException e) {
            System.out.println("Failed to create new input stream from audio filename");
        }

        return inputStream;
    }

    private Recognizer createRecognizer() {
        return new Recognizer(
                createModel(),
                SAMPLING_RATE
        );
    }

    private Model createModel() {

        Path modelPath = Paths.get(MODEL_DIRECTORY);
        if (Files.exists(modelPath)) {
            return new Model(MODEL_DIRECTORY);
        } else {
            System.out.println("Model directory " + MODEL_DIRECTORY + " not found. Exiting.");
            System.exit(1);
            return new Model(MODEL_DIRECTORY);
        }
    }

    private void createOutputFiles() {
        createTranscriptFile();
        createTimestampFile();
    }

    private void printMainRecognizerResults(Recognizer recognizer, InputStream audioInputStream) {
        int numberBytes;
        byte[] inputBuffer = new byte[AUDIO_BYTE_ARRAY_SIZE];

        numberBytes = readBytesFromInputStream(audioInputStream, inputBuffer);

        while (numberBytes >= 0) {
            if (recognizer.acceptWaveForm(inputBuffer, numberBytes)) {
                String jsonResult = recognizer.getResult();
                printWordsTimestampsToTempFiles(jsonResult);
            }

            numberBytes = readBytesFromInputStream(audioInputStream, inputBuffer);
        }
    }

    private int readBytesFromInputStream(InputStream audioInputStream, byte[] inputBuffer) {
        return readStreamIntoInputBuffer(inputBuffer, audioInputStream);
    }

    private int readStreamIntoInputBuffer(byte[] inputBuffer, InputStream audioInputStream) {
        Integer result = null;

        try {
            result = audioInputStream.read(inputBuffer);
        } catch (java.io.IOException e) {
            System.out.println("Error reading bytes from the audio input stream (derived from the audio file)");
            e.printStackTrace();
        }
        return result;
    }

    private void printWordsTimestampsToTempFiles(String jsonInput) {
        JsonObject jsonParseObject = getJsonObject(jsonInput);

        if (jsonParseObject.has("result")) {
            JsonArray allTimestampedWords = getAllWords(jsonParseObject);
            printWordsTimestampsToFiles(allTimestampedWords);
        }
    }

    private JsonObject getJsonObject(String input) {
        return new Gson().fromJson(input, JsonObject.class);
    }

    private JsonArray getAllWords(JsonObject jsonObject) {
        return jsonObject.get("result").getAsJsonArray();
    }

    private void printWordsTimestampsToFiles(JsonArray allTimestampedWords) {
        for (JsonElement wordInfo : allTimestampedWords) {
            if (wordInfo.isJsonObject()) {
                String startTime = getStartTime(wordInfo);
                String word = getWord(wordInfo);
                putWordInTempFile(word);
                putTimestampInTempFile(startTime);
            }
        }
    }

    private String getStartTime(JsonElement wordInfo) {
        return wordInfo.getAsJsonObject().get("start").getAsString();
    }

    private String getWord(JsonElement wordInfo) {
        return wordInfo.getAsJsonObject().get("word").getAsString();
    }

    private void putWordInTempFile(String word) {
        try {
            String transcriptFilename = cacheKey.getTranscriptFilename();
            Path outputFile = Path.of(transcriptFilename);
            Files.writeString(outputFile, appendNewlineTo(word), StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("Error printing sound translation to temporary file");
        }
    }

    private void putTimestampInTempFile(String timestamp) {
        try {
            String timestampFilename = cacheKey.getTimestampFilename();
            Path outputFile = Path.of(timestampFilename);
            Files.writeString(outputFile, appendNewlineTo(timestamp), StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("Error printing sound translation to temporary file");
        }
    }

    private void createTimestampFile() {
        try {
            File timestamps = new File(cacheKey.getTimestampFilename());
            System.out.println("Creating timestamp file:" + timestamps.toString());
            timestamps.delete();
            timestamps.createNewFile();
        } catch (IOException e) {
            System.out.println("Error creating timestamp file.");
        }
    }

    private void createTranscriptFile() {
        try {
            File transcript = new File(cacheKey.getTranscriptFilename());
            System.out.println("Creating transcript file:" + transcript.toString() + " thread id is " + Thread.currentThread().getId() + " file to search is " + cacheKey.filename + " object id is " + System.identityHashCode(this));
            transcript.delete();
            transcript.createNewFile();
        } catch (IOException e) {
            System.out.println("Error creating transcript file.");
        }
    }

    private void printFinalRecognizerResult(Recognizer recognizer) {
        String finalJsonResult = recognizer.getFinalResult();
        printWordsTimestampsToTempFiles(finalJsonResult);
    }

    private String appendNewlineTo(String string) {
        return string + '\n';
    }
}
