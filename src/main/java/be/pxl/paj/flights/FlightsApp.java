package be.pxl.paj.flights;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Command-line client allowing users to log in, search for flights, reserve
 * seats, show their reservations, and cancel reservations.
 */
public class FlightsApp {

	/**
	 * Provides access to the database.
	 */
	private final FlightsDB db;

	/**
	 * Stores the authenticated user (if any).
	 */
	private User user;

	/**
	 * Stores the most recently displayed list of itineraries.
	 */
	private List<Itinerary> itineraries;

	/**
	 * Type of command (search|reservations) that produced the list above.
	 */
	private String itinerariesCommand;

	public FlightsApp(FlightsDB db) {
		this.db = db;
	}

	/**
	 * Runs the client until the user quits.
	 */
	public void run() throws Exception {
		System.out.println("Enter a command (type 'help' for usage information):");
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			System.out.print("> ");
			System.out.flush();

			String[] tokens = tokenize(in.readLine());
			if (tokens == null) {
				break;
			}
			if (tokens.length == 0) {
				continue;
			}

			if (tokens[0].equals("help") && tokens.length == 1) {
				help();
			} else if (tokens[0].equals("login") && tokens.length == 3) {
				runLogin(tokens[1], tokens[2]);
			} else if (tokens[0].equals("search") && tokens.length == 4) {
				runSearch(tokens[1], tokens[2], Integer.parseInt(tokens[3]));
			} else if (tokens[0].equals("book") && tokens.length == 2) {
				runBook(Integer.parseInt(tokens[1]));
			} else if (tokens[0].equals("reservations") && tokens.length == 1) {
				runReservations();
			} else if (tokens[0].equals("cancel") && tokens.length == 2) {
				runCancel(Integer.parseInt(tokens[1]));
			} else if (tokens[0].equals("quit") && tokens.length == 1) {
				break;
			} else {
				System.out.println("Error: unknown command");
				System.out.println();
				help();
			}
			System.out.println();
		}
	}

	/**
	 * Pattern for a single token of input, which is either a quoted string or a
	 * sequence of non-whitespace characters.
	 */
	private static final Pattern TOKEN_PATTERN =
			Pattern.compile("\"([^\"]*)\"|(\\S+)");

	/**
	 * Parses a line of input into tokens (see {@code TOKEN_PATTERN}).
	 */
	private static String[] tokenize(String command) {
		if (command == null) {
			return null;
		}
		Matcher m = TOKEN_PATTERN.matcher(command);
		List<String> tokens = new ArrayList<String>();
		while (m.find()) {
			if (m.group(1) != null) {
				tokens.add(m.group(1));
			} else {
				tokens.add(m.group(2));
			}
		}
		return tokens.toArray(new String[0]);
	}

	/**
	 * Lists the supported commands for the user.
	 */
	private static void help() {
		System.out.println("Supported commands:");
		System.out.println(" * login <handle> <password>");
		System.out.println(" * search <origin-city> <dest-city> <day-of-month>");
		System.out.println(" * book <itinerary-num>");
		System.out.println(" * reservations");
		System.out.println(" * cancel <itinerary-num>");
		System.out.println(" * quit");
	}

	/**
	 * Executes the login command with the given parameters.
	 */
	private void runLogin(String handle, String password) throws Exception {
		User user = db.logIn(handle, password);
		if (user != null) {
			this.user = user;
			System.out.println(String.format("Hello, %s!", user.getFullName()));
		} else {
			System.out.println("Error: incorrect handle or password");
		}
	}

	/**
	 * Executes the search command with the given parameters.
	 */
	private void runSearch(String originCity, String destCity, int dayOfMonth)
			throws Exception {
		LocalDate date = LocalDate.now().withMonth(7).withDayOfMonth(dayOfMonth);
		itineraries = toItineraries(db.getFlights(date, originCity, destCity));
		itinerariesCommand = "search";

		// Sort the itineraries and truncate to 99 results.
		Collections.sort(itineraries);
		itineraries = itineraries.subList(0, 99);

		showItineraries();
	}

	/**
	 * Executes the book with the given parameters.
	 */
	private void runBook(int itineraryNum) throws Exception {
		if (!"search".equals(itinerariesCommand)) {
			System.out.println("Error: book is only allowed after a search");

		} else if (user == null) {
			System.out.println("Error: must be logged in to book flights");

		} else if (itineraryNum <= 0 || itineraries.size() < itineraryNum) {
			System.out.println(String.format("Error: no such itinerary (%d)",
					itineraryNum));

		} else {
			Itinerary it = itineraries.get(itineraryNum - 1);
			int result = db.addReservations(user, it.getDate(), it.getFlights());
			if (result == FlightsDB.RESERVATION_ADDED) {
				System.out.println("Successfully booked flights");
			} else if (result == FlightsDB.RESERVATION_FLIGHT_FULL) {
				System.out.println("Error: that flight is already full");
			} else if (result == FlightsDB.RESERVATION_DAY_FULL) {
				System.out.println("Error: you already have a reservation on that day");
			} else {
				assert false : "impossible";
			}
		}
	}

	/**
	 * Executes the reservations.
	 */
	private void runReservations() throws Exception {
		if (user == null) {
			System.out.println("Error: must be logged in to show reservations");

		} else {
			itineraries = toItineraries(db.getReservations(user));
			itinerariesCommand = "reservations";

			showItineraries();
		}
	}

	/**
	 * Executes the cancel with the given parameters.
	 */
	private void runCancel(int itineraryNum) throws Exception {

		if (!"reservations".equals(itinerariesCommand)) {
			System.out.println("Error: cancel is only allowed after reservations");

		} else if (user == null) {
			System.out.println("Error: must be logged in to cancel flights");

		} else if (itineraryNum <= 0 || itineraries.size() < itineraryNum) {
			System.out.println(String.format("Error: no such itinerary (%d)",
					itineraryNum));

		} else {
			Itinerary it = itineraries.get(itineraryNum - 1);
			db.removeReservations(user, it.getFlights());
			System.out.println("Successfully canceled flights");
		}

	}


	/**
	 * Groups the given flights into iterinaries. We can do this under the
	 * assumption that flights are on the same day iff they are in an iterinary.
	 */
	private static List<Itinerary> toItineraries(List<Flight> flights) {
		Map<LocalDate, List<Flight>> flightsByDate = new HashMap<>();
		for (Flight flight : flights) {
			if (!flightsByDate.containsKey(flight.getDate())) {
				flightsByDate.put(flight.getDate(), new ArrayList<Flight>());
			}
			flightsByDate.get(flight.getDate()).add(flight);
		}
		List<Flight[]> groups = new ArrayList<>();
		for (List<Flight> fs : flightsByDate.values()) {
			groups.add(fs.toArray(new Flight[fs.size()]));
		}
		return toItineraries(groups);
	}

	/**
	 * Returns an itinerary for each group of flights.
	 */
	private static List<Itinerary> toItineraries(
			Collection<Flight[]> groups) {
		List<Itinerary> itineraries = new ArrayList<>();
		for (Flight[] flights : groups) {
			itineraries.add(new Itinerary(Arrays.asList(flights)));
		}
		return itineraries;
	}

	/**
	 * Format to use for displaying dates.
	 */
	private static DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

	/**
	 * Prints out the most recently stored itineraries.
	 */
	private void showItineraries() {
		assert itineraries != null;
		for (int index = 0; index < itineraries.size(); index++) {
			Itinerary it = itineraries.get(index);
			Flight firstFlight = it.getFlights().get(0);
			System.out.println(String.format("%2d %11s %s", index + 1, DATE_FMT.format(it.getDate()), firstFlight.getFlightDetails()));
			for (int j = 1; j < it.getFlights().size(); j++) {
				Flight flight = it.getFlights().get(j);
				System.out.println(String.format("%15s %s", " ", flight.getFlightDetails()));
			}
		}
	}

	public static void main(String[] args) throws Exception {
		Path settingsFile = Path.of("src/main/resources/dbconn.properties");
		FlightsDB db = new FlightsDB();

		if (args.length > 0) {
			if ("-settings".equals(args[0]) && args.length == 2) {
				settingsFile = Path.of(args[1]);
			} else {
				System.err.println(
						"Usage: java FlightsApp [-settings settings_file]");
				System.exit(1);
			}
		}

		Properties settings = new Properties();
		settings.load(Files.newInputStream(settingsFile));
		db.open(settings);

		try {
			db.init();
			FlightsApp app = new FlightsApp(db);
			app.run();
		} finally {
			db.close();
		}
	}
}

