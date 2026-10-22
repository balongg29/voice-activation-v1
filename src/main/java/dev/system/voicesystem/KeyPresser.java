package dev.system.voicesystem;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Presses a key inside the game's own input system so every keybind from every mod sees it. */
public final class KeyPresser {
	private KeyPresser() {}

	public static void press(String keyName, int holdMillis) {
		final InputConstants.Key key;
		try {
			key = InputConstants.getKey(keyName);
		} catch (Exception e) {
			VoiceSystemClient.LOGGER.warn("Unknown key name '{}'", keyName);
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			KeyMapping.set(key, true);
			KeyMapping.click(key);
		});

		Thread releaser = new Thread(() -> {
			try {
				Thread.sleep(Math.max(1, holdMillis));
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			mc.execute(() -> KeyMapping.set(key, false));
		}, "voicesystem-key-release");
		releaser.setDaemon(true);
		releaser.start();
	}
}
