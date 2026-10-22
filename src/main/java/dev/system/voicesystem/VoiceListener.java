package dev.system.voicesystem;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Listens to the microphone offline and fires key presses when a trigger word is heard. */
public class VoiceListener {
	private final VoiceConfig config;
	private final Path modelDir;
	private final Map<String, VoiceConfig.Trigger> byWord = new HashMap<>();
	private volatile boolean running;
	private Thread thread;

	public VoiceListener(VoiceConfig config, Path modelDir) {
		this.config = config;
		this.modelDir = modelDir;
		for (VoiceConfig.Trigger t : config.triggers) {
			if (t.word != null && t.key != null) byWord.put(t.word.toLowerCase().trim(), t);
		}
	}

	public void start() {
		if (!Files.isDirectory(modelDir)) {
			VoiceSystemClient.LOGGER.error(
				"Voice model not found at {}. Download vosk-model-small-en-us-0.15 from "
					+ "https://alphacephei.com/vosk/models, unzip it, and put its contents in that folder.",
				modelDir);
			return;
		}
		running = true;
		thread = new Thread(this::run, "voicesystem-listener");
		thread.setDaemon(true);
		thread.start();
	}

	public void stop() {
		running = false;
	}

	private void run() {
		LibVosk.setLogLevel(LogLevel.WARNINGS);

		// Limiting Vosk to our trigger words makes it far more accurate and less likely to misfire.
		String grammar = byWord.keySet().stream()
			.map(w -> "\"" + w + "\"")
			.collect(Collectors.joining(", ", "[", ", \"[unk]\"]"));

		AudioFormat format = new AudioFormat(16000f, 16, 1, true, false);
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

		try (Model model = new Model(modelDir.toString());
			 Recognizer recognizer = new Recognizer(model, 16000f, grammar);
			 TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(info)) {

			mic.open(format);
			mic.start();
			VoiceSystemClient.LOGGER.info("Voice System is listening for phrases: {}", byWord.keySet());

			byte[] buffer = new byte[4096];
			while (running) {
				int read = mic.read(buffer, 0, buffer.length);
				if (read <= 0) continue;
				if (recognizer.acceptWaveForm(buffer, read)) {
					handle(recognizer.getResult());
				}
			}
		} catch (Throwable t) {
			VoiceSystemClient.LOGGER.error("Voice listener stopped", t);
		}
	}

	private void handle(String json) {
		try {
			JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
			String text = obj.has("text") ? obj.get("text").getAsString().trim().toLowerCase() : "";
			if (text.isEmpty()) return;

			// Triggers can be single words or phrases like "system open". Try the longest phrase
			// first and fire only once per utterance, so "system open" never also fires "system".
			String padded = " " + text + " ";
			VoiceConfig.Trigger best = null;
			for (Map.Entry<String, VoiceConfig.Trigger> entry : byWord.entrySet()) {
				if (padded.contains(" " + entry.getKey() + " ")
					&& (best == null || entry.getKey().length() > best.word.trim().length())) {
					best = entry.getValue();
				}
			}
			if (best != null) {
				VoiceSystemClient.LOGGER.info("Heard '{}', pressing {}", best.word, best.key);
				KeyPresser.press(best.key, best.holdMillis);
			}
		} catch (Exception e) {
			VoiceSystemClient.LOGGER.warn("Could not read recognizer output: {}", json);
		}
	}
}
