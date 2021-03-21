package be.pxl.paj.flights;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

/**
 * Allows clients to query and update the database in order to log in, search
 * for flights, reserve seats, show reservations, and cancel reservations.
 */
public class FlightsDB {
    private static final Logger LOGGER = LogManager.getLogger(FlightsDB.class);
    /**
     * Maximum number of reservations to allow on one flight.
     */
    private static int MAX_FLIGHT_BOOKINGS = 3;
    private static final String SELECT_CUSTOMER_QUERY = "SELECT * FROM CUSTOMER WHERE handle = ? AND password = ?";
    private static final String DIRECT_FLIGHTS_QUERY = "SELECT fid, name, flight_num, origin_city, dest_city, actual_time " +
            "FROM FLIGHTS " +
            "INNER JOIN CARRIERS c ON carrier_id = c.cid " +
            "WHERE actual_time IS NOT NULL AND " +
            "year = ? AND month_id = ? AND day_of_month = ? AND " +
            "origin_city = ? AND dest_city = ? " +
            "ORDER BY actual_time ASC LIMIT 99";
    private static final String TWO_HOP_FLIGHTS_QUERY = "SELECT f1.fid as fid1, c1.name as name1, " +
            "f1.flight_num as flight_num1, f1.origin_city as origin_city1, " +
            "f1.dest_city as dest_city1, f1.actual_time as actual_time1, " +
            "f2.fid as fid2, c2.name as name2, " +
            "f2.flight_num as flight_num2, f2.origin_city as origin_city2, " +
            "f2.dest_city as dest_city2, f2.actual_time as actual_time2 " +
            "FROM FLIGHTS f1 " +
            "INNER JOIN FLIGHTS f2 on f1.dest_city = f2.origin_city " +
            "AND f2.year = f2.year AND f2.month_id = f1.month_id " +
            "AND f2.day_of_month = f1.day_of_month " +
            "INNER JOIN CARRIERS c1 on c1.cid = f1.carrier_id " +
            "INNER JOIN CARRIERS c2 on c2.cid = f2.carrier_id " +
            "WHERE f1.actual_time IS NOT NULL AND " +
            "f2.actual_time IS NOT NULL AND " +
            "f1.year = ? AND f1.month_id = ? AND f1.day_of_month = ? AND " +
            "f1.origin_city = ? AND f2.dest_city = ? " +
            "ORDER BY f1.actual_time + f2.actual_time ASC LIMIT 99";
    private static final String RESERVATIONS_QUERY = "SELECT f.fid, name, flight_num, origin_city, dest_city, actual_time, year, month_id, day_of_month " +
            "FROM RESERVATION r " +
            "INNER JOIN FLIGHTS f on r.fid = f.fid " +
            "INNER JOIN CARRIERS c on f.carrier_id = c.cid " +
            "WHERE r.uid = ?";
    private static final String COUNT_FLIGHT_RESERVATIONS_QUERY = "SELECT COUNT(*) AS count FROM RESERVATION WHERE fid = ?";
    private static final String COUNT_USER_RESERVATIONS_ON_DATE = "SELECT COUNT(r.uid) AS count " +
            "FROM RESERVATION r " +
            "INNER JOIN FLIGHTS f on f.fid = r.fid " +
            "WHERE r.uid = ? AND f.YEAR = ? AND f.month_id = ? AND f.day_of_month = ?";
    private static final String INSERT_RESERVATION_QUERY = "INSERT INTO RESERVATION(uid, fid) VALUES(?, ?)";
    private static final String DELETE_RESERVATION_QUERY = "DELETE FROM RESERVATION WHERE uid = ? AND fid = ?";
    /**
     * Holds the connection to the database.
     */
    private Connection conn;
    private final Scanner scanner = new Scanner(System.in);

    /**
     * Perpared statements
     */
    private PreparedStatement selectCustomer;
    private PreparedStatement directFlights;
    private PreparedStatement twoHopFlights;
    private PreparedStatement reservations;
    private PreparedStatement countFlightReservations;
    private PreparedStatement countUserReservationsOnDate;
    private PreparedStatement insertReservation;
    private PreparedStatement deleteReservation;

    /**
     * Opens a connection to the database using the given settings.
     */
    public void open(Properties settings) throws Exception {
        // Make sure the JDBC driver is loaded.
        // Open a connection to our database.
        conn = DriverManager.getConnection(
                settings.getProperty("flightservice.url"),
                settings.getProperty("flightservice.username"),
                settings.getProperty("flightservice.password"));
    }

    /**
     * Closes the connection to the database.
     */
    public void close() throws SQLException {
        conn.close();
        scanner.close();
        conn = null;
    }

    /**
     * Performs additional preparation after the connection is opened.
     */
    public void init() throws SQLException {
        selectCustomer = conn.prepareStatement(SELECT_CUSTOMER_QUERY);
        directFlights = conn.prepareStatement(DIRECT_FLIGHTS_QUERY);
        twoHopFlights = conn.prepareStatement(TWO_HOP_FLIGHTS_QUERY);
        reservations = conn.prepareStatement(RESERVATIONS_QUERY);
        countFlightReservations = conn.prepareStatement(COUNT_FLIGHT_RESERVATIONS_QUERY);
        countUserReservationsOnDate = conn.prepareStatement(COUNT_USER_RESERVATIONS_ON_DATE);
        insertReservation = conn.prepareStatement(INSERT_RESERVATION_QUERY);
        deleteReservation = conn.prepareStatement(DELETE_RESERVATION_QUERY);
    }

    /**
     * Tries to log in as the given user.
     *
     * @return The authenticated user or null if login failed.
     */
    public User logIn(String handle, String password) throws SQLException {
        selectCustomer.setString(1, handle);
        selectCustomer.setString(2, password);

        ResultSet resultSet = selectCustomer.executeQuery();
        if (resultSet.next()) {
            return new User(resultSet.getInt(1), resultSet.getString(3), resultSet.getString(2));
        } else {
            LOGGER.error("Invalid credentials: " + handle + ", " + password);
            return null;
        }
    }

    /**
     * Returns the list of all flights between the given cities on the given day.
     */
    public List<Flight[]> getFlights(LocalDate date, String originCity, String destCity) throws SQLException {
        List<Flight[]> results = new ArrayList<>();
        directFlights.setInt(1, date.getYear());
        directFlights.setInt(2, date.getMonthValue());
        directFlights.setInt(3, date.getDayOfMonth());
        directFlights.setString(4, originCity);
        directFlights.setString(5, destCity);
        ResultSet directResults = directFlights.executeQuery();

        while (directResults.next()) {
            results.add(new Flight[]{
                    new Flight(directResults.getInt("fid"), date,
                            directResults.getString("name"),
                            directResults.getString("flight_num"),
                            directResults.getString("origin_city"),
                            directResults.getString("dest_city"),
                            (int) directResults.getFloat("actual_time"))
            });
        }
        directResults.close();

        twoHopFlights.setInt(1, date.getYear());
        twoHopFlights.setInt(2, date.getMonthValue());
        twoHopFlights.setInt(3, date.getDayOfMonth());
        twoHopFlights.setString(4, originCity);
        twoHopFlights.setString(5, destCity);
        ResultSet twoHopResults = twoHopFlights.executeQuery();

        while (twoHopResults.next()) {
            results.add(new Flight[]{
                    new Flight(twoHopResults.getInt("fid1"), date,
                            twoHopResults.getString("name1"),
                            twoHopResults.getString("flight_num1"),
                            twoHopResults.getString("origin_city1"),
                            twoHopResults.getString("dest_city1"),
                            (int) twoHopResults.getFloat("actual_time1")),
                    new Flight(twoHopResults.getInt("fid2"), date,
                            twoHopResults.getString("name2"),
                            twoHopResults.getString("flight_num2"),
                            twoHopResults.getString("origin_city2"),
                            twoHopResults.getString("dest_city2"),
                            (int) twoHopResults.getFloat("actual_time2"))
            });
        }
        twoHopResults.close();

        return results;
    }

    /**
     * Returns the list of all flights reserved by the given user.
     */
    public List<Flight> getReservations(User user) throws SQLException {
        List<Flight> allReservations = new ArrayList<>();
        reservations.setInt(1, user.getId());
        ResultSet results = reservations.executeQuery();
        while (results.next()) {
            LocalDate reservationDate = LocalDate.of(results.getInt("year"), results.getInt("month_id"), results.getInt("day_of_month"));
            allReservations.add(
                    new Flight(results.getInt("fid"), reservationDate,
                            results.getString("name"),
                            results.getString("flight_num"),
                            results.getString("origin_city"),
                            results.getString("dest_city"),
                            results.getInt("actual_time")
                    )
            );
        }
        return allReservations;
    }

    /**
     * Indicates that a reservation was added successfully.
     */
    public static final int RESERVATION_ADDED = 1;

    /**
     * Indicates the reservation could not be made because the flight is full
     * (i.e., 3 users have already booked).
     */
    public static final int RESERVATION_FLIGHT_FULL = 2;

    /**
     * Indicates the reservation could not be made because the user already has a
     * reservation on that day.
     */
    public static final int RESERVATION_DAY_FULL = 3;

    /**
     * Attempts to add a reservation for the given user on the given flights, all
     * occurring on the given day.
     *
     * @return One of the {@code RESERVATION_*} codes above.
     */
    public int addReservations(User user, LocalDate date, List<Flight> flights)
            throws SQLException {
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        int numberOfReservations = countReservationsForUserOnDate(user, date);
        if (numberOfReservations > 0) {
            return RESERVATION_DAY_FULL;
        }
        for (Flight flight : flights) {
            numberOfReservations = countReservationsForFlight(flight);
            if (numberOfReservations >= MAX_FLIGHT_BOOKINGS) {
                conn.rollback();
                return RESERVATION_FLIGHT_FULL;
            }
            insertReservation.setInt(1, user.getId());
            insertReservation.setInt(2, flight.getId());
            insertReservation.execute();
        }
        System.out.println("Press enter to continue..");
        scanner.nextLine();
        conn.commit();
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return RESERVATION_ADDED;
    }

    private int countReservationsForFlight(Flight flight) throws SQLException {
        countFlightReservations.setInt(1, flight.getId());
        ResultSet resultSet = countFlightReservations.executeQuery();
        if (resultSet.next()) {
            return resultSet.getInt("count");
        }
        return -1;
    }

    private int countReservationsForUserOnDate(User user, LocalDate date) throws SQLException {
        countUserReservationsOnDate.setInt(1, user.getId());
        countUserReservationsOnDate.setInt(2, date.getYear());
        countUserReservationsOnDate.setInt(3, date.getMonthValue());
        countUserReservationsOnDate.setInt(4, date.getDayOfMonth());
        ResultSet resultSet = countUserReservationsOnDate.executeQuery();
        if (resultSet.next()) {
            return resultSet.getInt("count");
        }
        return -1;
    }

    /**
     * Cancels all reservations for the given user on the given flights.
     */
    public void removeReservations(User user, List<Flight> flights)
            throws SQLException {
        conn.setAutoCommit(false);
        try {
            for (Flight flight : flights) {
                deleteReservation.setInt(1, user.getId());
                deleteReservation.setInt(2, flight.getId());
                deleteReservation.execute();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
        } finally {
            conn.setAutoCommit(false);
        }
    }
}
