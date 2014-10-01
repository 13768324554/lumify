package io.lumify.rdf;

import io.lumify.core.cmdline.CommandLineBase;
import io.lumify.core.exception.LumifyException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;

import java.io.File;

public class RdfImportTool extends CommandLineBase {
    public static void main(String[] args) throws Exception {
        int res = new RdfImportTool().run(args);
        if (res != 0) {
            System.exit(res);
        }
    }

    @Override
    protected Options getOptions() {
        Options options = super.getOptions();

        options.addOption(
                OptionBuilder
                        .withLongOpt("in")
                        .withDescription("Input file")
                        .isRequired()
                        .hasArg(true)
                        .withArgName("filename")
                        .create("i")
        );

        return options;
    }

    @Override
    protected int run(CommandLine cmd) throws Exception {
        String inputFileName = cmd.getOptionValue("in");
        File inputFile = new File(inputFileName);
        if (!inputFile.exists()) {
            throw new LumifyException("Could not find file: " + inputFileName);
        }

        new RdfImport().importRdf(getGraph(), inputFile, getAuthorizations());
        getGraph().flush();

        return 0;
    }
}
