/*
 * Copyright 2000-2018 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.flow.server.frontend;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.server.FallibleCommand;

import static com.vaadin.flow.server.frontend.FrontendUtils.WEBPACK_CONFIG;
import static com.vaadin.flow.server.frontend.FrontendUtils.WEBPACK_GENERATED;

/**
 * Updates the webpack config file according with current project settings.
 */
public class TaskUpdateWebpack implements FallibleCommand {

    /**
     * The name of the webpack config file.
     */
    private final String webpackTemplate;
    private final String webpackGeneratedTemplate;
    private final transient Path webpackOutputPath;
    private final transient Path flowImportsFilePath;
    private final transient Path webpackConfigPath;
    private final transient Path frontendDirectory;
    private final boolean isClientSideMode;

    /**
     * Create an instance of the updater given all configurable parameters.
     *
     * @param frontendDirectory
     *            the directory used for {@code Frontend} alias
     * @param webpackConfigFolder
     *            folder with the `webpack.config.js` file.
     * @param webpackOutputDirectory
     *            the directory to set for webpack to output its build results.
     * @param webpackTemplate
     *            name of the webpack resource to be used as template when
     *            creating the <code>webpack.config.js</code> file.
     * @param webpackGeneratedTemplate
     *            name of the webpack resource to be used as template when
     *            creating the <code>webpack.generated.js</code> file.
     * @param generatedFlowImports
     *            name of the JS file to update with the Flow project imports
     * @param isClientSideMode
     *            whether the application running with clientSideBootstrapMode* 
     */
    TaskUpdateWebpack(File frontendDirectory, File webpackConfigFolder,
            File webpackOutputDirectory, String webpackTemplate,
            String webpackGeneratedTemplate, File generatedFlowImports,
            boolean isClientSideMode) {
        this.frontendDirectory = frontendDirectory.toPath();
        this.webpackTemplate = webpackTemplate;
        this.webpackGeneratedTemplate = webpackGeneratedTemplate;
        this.webpackOutputPath = webpackOutputDirectory.toPath();
        this.flowImportsFilePath = generatedFlowImports.toPath();
        this.webpackConfigPath = webpackConfigFolder.toPath();
        this.isClientSideMode = isClientSideMode;
    }

    @Override
    public void execute() {
        try {
            createWebpackConfig();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void createWebpackConfig() throws IOException {
        if (webpackTemplate == null || webpackTemplate.trim().isEmpty()) {
            return;
        }

        File configFile = new File(webpackConfigPath.toFile(), WEBPACK_CONFIG);

        // If we have an old config file we remove it and create the new one
        // using the webpack.generated.js
        if (configFile.exists()) {
            if (!FrontendUtils.isWebpackConfigFile(configFile)) {
                log().warn(
                        "Flow generated webpack configuration was not mentioned "
                                + "in the configuration file: {}."
                                + "Please verify that './webpack.generated.js' is used "
                                + "in the merge or remove the file to generate a new one.",
                        configFile);
            }
        } else {
            URL resource = this.getClass().getClassLoader()
                    .getResource(webpackTemplate);
            FileUtils.copyURLToFile(resource, configFile);
            log().info("Created webpack configuration file: '{}'", configFile);
        }

        // Generated file is always re-written
        File generatedFile = new File(webpackConfigPath.toFile(),
                WEBPACK_GENERATED);

        URL resource = this.getClass().getClassLoader()
                .getResource(webpackGeneratedTemplate);
        FileUtils.copyURLToFile(resource, generatedFile);
        List<String> lines = modifyWebpackConfig(generatedFile);

        FileUtils.writeLines(generatedFile, lines);
    }

    private List<String> modifyWebpackConfig(File generatedFile)
            throws IOException {
        List<String> lines = FileUtils.readLines(generatedFile, "UTF-8");

        String frontendLine = "const frontendFolder = require('path').resolve(__dirname, '"
                + getEscapedRelativeWebpackPath(frontendDirectory) + "');";

        String outputLine = "const mavenOutputFolderForFlowBundledFiles = require('path').resolve(__dirname, '"
                + getEscapedRelativeWebpackPath(webpackOutputPath) + "');";
        String mainLine = "const fileNameOfTheFlowGeneratedMainEntryPoint = require('path').resolve(__dirname, '"
                + getEscapedRelativeWebpackPath(flowImportsFilePath) + "');";
        String isClientSideBootstrapModeLine = "const useClientSideIndexFileForBootstrapping = "
                + isClientSideMode + ";";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(
                    "const fileNameOfTheFlowGeneratedMainEntryPoint")) {
                lines.set(i, mainLine);
            }
            if (lines.get(i)
                    .startsWith("const mavenOutputFolderForFlowBundledFiles")) {
                lines.set(i, outputLine);
            }
            if (lines.get(i).startsWith("const frontendFolder")) {
                lines.set(i, frontendLine);
            }
            if (lines.get(i).startsWith("const useClientSideIndexFileForBootstrapping")) {
                lines.set(i, isClientSideBootstrapModeLine);
            }
        }
        return lines;
    }

    private String getEscapedRelativeWebpackPath(Path path) {
        Path relativePath = path.isAbsolute()
                ? webpackConfigPath.relativize(path)
                : path;
        return relativePath.toString().replaceAll("\\\\", "/");
    }

    private Logger log() {
        // Using short prefix so as npm output is more readable
        return LoggerFactory.getLogger("dev-updater");
    }
}