/*
	Copyright 2012-2015, Merten Peetz

	This file is part of OsmPoisPbf.
	OsmPoisPbf is free software: you can redistribute it and/or modify it under the terms of the GNU 
	General Public License as published by the Free Software Foundation, either version 3 of the 
	License, or (at your option) any later version.

	OsmPoisPbf is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without 
	even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
	General Public License for more details.

	You should have received a copy of the GNU General Public License along with OsmPoisPbj. If not, 
	see http://www.gnu.org/licenses/.
 */

package de.morbz.osmpoispbf;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import de.morbz.osmpoispbf.utils.StopWatch;
import net.morbz.osmonaut.EntityFilter;
import net.morbz.osmonaut.IOsmonautReceiver;
import net.morbz.osmonaut.Osmonaut;
import net.morbz.osmonaut.osm.Entity;
import net.morbz.osmonaut.osm.EntityType;
import net.morbz.osmonaut.osm.LatLon;
import net.morbz.osmonaut.osm.Tags;
import net.morbz.osmonaut.osm.Way;

public class Scanner {
	// Const
	private static final String VERSION = "v1.2";

	// Vars
	private static Writer writer;
	private static List<Filter> filters;
	private static Options options;
	private static boolean onlyClosedWays = true;
	private static boolean printPois = false;
	private static int poisFound = 0;
	private static String[] requiredTags = { "name" };
	private static String[] outputTags = { "name" };
	private static long lastMillis = 0;
	private static char separator = '|';
	private static boolean skipRequiredTags = false;

	public static void main(String[] args) {
		System.out.println("OsmPoisPbf " + VERSION + " started");

		// Get input file
		if(args.length < 1) {
			System.out.println("Error: Please provide an input file");
			System.exit(-1);
		}
		String inputFile = args[args.length - 1];

		// Get output file
		String outputFile;
		int index = inputFile.indexOf('.');
		if(index != -1) {
			outputFile = inputFile.substring(0, index);
		} else {
			outputFile = inputFile;
		}
		outputFile += ".csv";

		// Setup CLI parameters
		options = new Options();
		options.addOption("ff", "filterFile", true, "The file that is used to filter categories");
		options.addOption("of", "outputFile", true, "The output CSV file to be written");
		options.addOption("rt", "requiredTags", true, "Comma separated list of tags that are required [name]");
		options.addOption("ot", "outputTags", true, "Comma separated list of tags that are exported [name]");
		options.addOption("ph", "printHeader", false, "Print CSV header as first line in the output file");
		options.addOption("r", "relations", false, "Parse relations");
		options.addOption("nw", "noWays", false, "Don't parse ways");
		options.addOption("nn", "noNodes", false, "Don't parse nodes");
		options.addOption("u", "allowUnclosedWays", false, "Allow ways that aren't closed");
		options.addOption("lm", "lowMemory", false, "Enables Low memory mode that stores caches on disk instead of memory");
		options.addOption("p", "processors", true, "Number of processors used to parse the PBF file");
		options.addOption("d", "decimals", true, "Number of decimal places of coordinates [7]");
		options.addOption("s", "separator", true, "Separator character for CSV [|]");
		options.addOption("v", "verbose", false, "Print all found POIs");
		options.addOption("srt", "skipRequiredTags", false, "Skip Required Tags. Sometimes there are records without required tags, but we still need them ");

		// Parse parameters
		CommandLine line = null;
		try {
			line = (new DefaultParser()).parse(options, args);
		} catch(ParseException exp) {
			System.err.println(exp.getMessage());
			printHelp();
			System.exit(-1);
		}

		// Help
		if(line.hasOption("help")) {
			printHelp();
			System.exit(0);
		}

		// Get filter file
		String filterFile = null;
		if(line.hasOption("filterFile")) {
			filterFile = line.getOptionValue("filterFile");
		}

		// Get output file
		if(line.hasOption("outputFile")) {
			outputFile = line.getOptionValue("outputFile");
		}

		// Check files
		if(inputFile.equals(outputFile)) {
			System.out.println("Error: Input and output files are the same");
			System.exit(-1);
		}
		File file = new File(inputFile);
		if(!file.exists()) {
			System.out.println("Error: Input file doesn't exist");
			System.exit(-1);
		}

		// Check OSM entity types
		boolean parseNodes = true;
		boolean parseWays = true;
		boolean parseRelations = false;
		if(line.hasOption("noNodes")) {
			parseNodes = false;
		}
		if(line.hasOption("noWays")) {
			parseWays = false;
		}
		if(line.hasOption("relations")) {
			parseRelations = true;
		}

		if(line.hasOption("skipRequiredTags")){
			skipRequiredTags = true;
		}

		// Unclosed ways allowed?
		if(line.hasOption("allowUnclosedWays")) {
			onlyClosedWays = false;
		}

		// Get CSV separator
		if(line.hasOption("separator")) {
			String arg = line.getOptionValue("separator");
			if(arg.length() != 1) {
				System.out.println("Error: The CSV separator has to be exactly 1 character");
				System.exit(-1);
			}
			separator = arg.charAt(0);
		}

		// Set decimals
		int decimals = 7; // OSM default
		if(line.hasOption("decimals")) {
			String arg = line.getOptionValue("decimals");
			try {
				int dec = Integer.valueOf(arg);
				if(dec < 0) {
					System.out.println("Error: Decimals must not be less than 0");
					System.exit(-1);
				} else {
					decimals = dec;
				}
			} catch(NumberFormatException ex) {
				System.out.println("Error: Decimals have to be a number");
				System.exit(-1);
			}
		}
		Poi.setDecimals(decimals);

		// Verbose mode?
		if(line.hasOption("verbose")) {
			printPois = true;
		}

		// Required tags
		if(line.hasOption("requiredTags")) {
			String arg = line.getOptionValue("requiredTags");
			requiredTags = arg.split(",");
		}

		// Output tags
		if(line.hasOption("outputTags")) {
			String arg = line.getOptionValue("outputTags");
			outputTags = arg.split(",");
		}

		// Get filter rules
		FilterFileParser parser = new FilterFileParser(filterFile);
		filters = parser.parse();
		if(filters == null) {
			System.exit(-1);
		}

		// Setup CSV output
		try {
			writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile),"UTF8"));
		} catch(IOException e) {
			System.out.println("Error: Output file error");
			System.exit(-1);
		}

		// Print Header
		if(line.hasOption("printHeader")) {
			// Build header
			String[] headerFields = { "category", "osm_id", "lat", "lon" };
			List<String> fields = new ArrayList<String>();
			fields.addAll(Arrays.asList(headerFields));
			fields.addAll(Arrays.asList(outputTags));

			// Write
			writeCsvLine(fields);
		}

		// Setup OSMonaut
		EntityFilter filter = new EntityFilter(parseNodes, parseWays, parseRelations);
		Osmonaut naut = new Osmonaut(inputFile, filter);
		naut.setWayNodeTags(false);

		// Low memory mode
		if(line.hasOption("lowMemory")) {
			naut.setStoreOnDisk(true);
		};

		// Number of processors
		if(line.hasOption("processors")) {
			int processors = 0;
			String arg = line.getOptionValue("processors");

			try {
				processors = Integer.valueOf(arg);
			} catch(NumberFormatException ex) {
				System.out.println("Error: Number of processors have to be a number");
				System.exit(-1);
			}

			if(processors < 1) {
				System.out.println("Error: Number of processors must not be less than 1");
				System.exit(-1);
			}

			naut.setProcessors(processors);
		}

		// Start watch
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();

		// Start OSMonaut
		naut.scan(new IOsmonautReceiver() {
			@Override
			public boolean needsEntity(EntityType type, Tags tags) {
				// Are there any tags?
				if(tags.size() == 0) {
					return false;
				}

				if(!skipRequiredTags){
					// Check required tags
					for(String tag : requiredTags) {
						if(!tags.hasKey(tag)) {
							return false;
						}
					}
				}

				// Check category
				return getCategory(tags, filters) != null;
			}

			@Override
			public void foundEntity(Entity entity) {
				// Check if way is closed
				if(onlyClosedWays && entity.getEntityType() == EntityType.WAY) {
					if(!((Way)entity).isClosed()) {
						return;
					}
				}

				// Get category
				Tags tags = entity.getTags();
				String cat = getCategory(tags, filters);
				if(cat == null) {
					return;
				}

				// Get center
				LatLon center = entity.getCenter();
				if(center == null) {
					return;
				}

				// Make OSM-ID
				String id = "";
				switch(entity.getEntityType()) {
				case NODE:
					id = "N";
					break;
				case WAY:
					id = "W";
					break;
				case RELATION:
					id = "R";
					break;
				}
				id += entity.getId();

				// Make output tags
				String[] values = new String[outputTags.length];
				for(int i = 0; i < outputTags.length; i++) {
					String key = outputTags[i];
					if(tags.hasKey(key)) {
						values[i] = tags.get(key);
					}
				}

				// Make POI
				poisFound++;
				Poi poi = new Poi(values, cat, center, id);

				// Output
				if(printPois) {
					System.out.println(poi);
				} else if(System.currentTimeMillis() > lastMillis + 20) {
					// Delay updating for a few milliseconds
					printPoisFound();
					lastMillis = System.currentTimeMillis();
				}

				// Write to file
				writeCsvLine(poi.getCsvFields());
			}
		});

		// Close writer
		try {
			writer.close();
		} catch(IOException e) {
			System.out.println("Error: Output file close error");
			System.exit(-1);
		}

		// Output results
		stopWatch.stop();

		printPoisFound();
		System.out.println();
		System.out.println("Elapsed time in milliseconds: " + stopWatch.getElapsedTime());

		// Quit
		System.exit(0);
	}

	private static void writeCsvLine(List<String> fields) {
		// Escape separator
		for(int i = 0; i < fields.size(); i++) {
			String field = fields.get(i);
			if(field == null) {
				field = "";
			}
			field = field.replace(separator, ' ');
			fields.set(i, field);
		}

		// Join fields
		String line = String.join(String.valueOf(separator), fields);

		// Write
		try {
			writer.write(line + "\n");
		} catch(IOException e) {
			System.out.println("Error: Output file write error");
			System.exit(-1);
		}
	}

	private static void printPoisFound() {
		// Output with carriage return
		String newStr = poisFound + " POIs found" + '\r';
		System.out.print(newStr);
	}

	// Print help
	private static void printHelp() {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("[-options] file", options);
	}

	/* Categories */
	private static String getCategory(Tags tags, List<Filter> filters) {
		// Iterate filters
		String cat = null;
		for(Filter filter : filters) {
			cat = getCategoryRecursive(filter, tags, null);
			if(cat != null) {
				return cat;
			}
		}
		return null;
	}

	private static String getCategoryRecursive(Filter filter, Tags tags, String key) {
		// Use key of parent rule or current
		if(filter.hasKey()) {
			key = filter.getKey();
		}

		// Check for key/value
		if(tags.hasKey(key)) {
			if(filter.hasValue() && !filter.getValue().equals(tags.get(key))) {
				return null;
			}
		} else {
			return null;
		}

		// If childs have categories, those will be used
		for(Filter child : filter.childs) {
			String cat = getCategoryRecursive(child, tags, key);
			if(cat != null) {
				return cat;
			}
		}
		return filter.getCategory();
	}
}
