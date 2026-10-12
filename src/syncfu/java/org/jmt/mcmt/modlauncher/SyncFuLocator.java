package org.jmt.mcmt.modlauncher;

import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.spi.FileSystemProvider;
import java.security.CodeSource;
import java.util.Collections;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

/**
 * Is a full spec'd ModLocator
 *
 * Unusable for it's intended purpose as it can't be registered as this jar uses the wrong classloader
 *
 * @author jediminer543
 *
 * @since 0.18.60
 *
 */
public class SyncFuLocator extends AbstractJarFileModLocator {

	private static final Logger LOGGER = LogManager.getLogger();
	private static final Marker M_LOCATOR = MarkerManager.getMarker("LOCATE");

	@Override
	public Stream<Path> scanCandidates() {
		LOGGER.info(M_LOCATOR, "Prepping locator...");
		try {
			CodeSource src = SyncFuLocator.class.getProtectionDomain().getCodeSource();
			URL jar = src.getLocation();
			if (!jar.toString().endsWith(".jar")) {
				LOGGER.warn(M_LOCATOR, "This be dev!!!");
				return Stream.empty();
			}
			URI uri = new URI("jar:".concat(jar.toString()).concat("!/"));
			//Thanks SO https://stackoverflow.com/a/48298758
			for (FileSystemProvider provider: FileSystemProvider.installedProviders()) {
				if (provider.getScheme().equalsIgnoreCase("jar")) {
					try {
						provider.getFileSystem(uri);
					} catch (FileSystemNotFoundException e) {
						// in this case we need to initialize it first:
						provider.newFileSystem(uri, Collections.emptyMap());
					}
				}
			}
			Path myPath = Paths.get(uri);
			System.out.println(myPath);
			Stream<Path> walk = Files.walk(myPath, 1).peek(p -> LOGGER.warn(M_LOCATOR, "Found {}", p)).filter(p -> p.toString().endsWith(".jar"));
			return walk;
		} catch (Exception e) {
			e.printStackTrace();
		}
		LOGGER.info(M_LOCATOR, "Locator complete!!!");
		return Stream.empty();
	}

	@Override
	public String name() {
		return "sync_fu_bundle";
	}

	@Override
	public void initArguments(java.util.Map<String, ?> arguments) {}

}
