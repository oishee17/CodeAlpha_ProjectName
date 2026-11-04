import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

/**
 * Console-based student grade manager.
 *
 * Features:
 *  - Add students with their grade (stored in an ArrayList)
 *  - Update or remove a student's grade
 *  - Compute average, highest and lowest scores
 *  - Display a full summary report of every student
 */
public class GradeManager {
    private static final double MIN_SCORE = 0.0;
    private static final double MAX_SCORE = 100.0;

    private final List<Student> students = new ArrayList<>();
    private final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        new GradeManager().run();
    }

    private void run() {
        System.out.println("=== Student Grade Manager ===");
        boolean running = true;
        while (running) {
            printMenu();
            String choice = prompt("Choose an option: ").trim();
            System.out.println();
            switch (choice) {
                case "1":
                    addStudent();
                    break;
                case "2":
                    updateGrade();
                    break;
                case "3":
                    removeStudent();
                    break;
                case "4":
                    showStats();
                    break;
                case "5":
                    showReport();
                    break;
                case "6":
                    sortByName();
                    break;
                case "7":
                    sortByScore();
                    break;
                case "0":
                    running = false;
                    break;
                default:
                    System.out.println("Invalid choice, please try again.");
            }
            System.out.println();
        }
        System.out.println("Goodbye!");
    }

    private void printMenu() {
        System.out.println("1. Add a student grade");
        System.out.println("2. Update a student grade");
        System.out.println("3. Remove a student");
        System.out.println("4. Show statistics (average / highest / lowest)");
        System.out.println("5. Show summary report");
        System.out.println("6. Sort students by name");
        System.out.println("7. Sort students by score (descending)");
        System.out.println("0. Exit");
    }

    // ----- Actions ---------------------------------------------------------

    private void addStudent() {
        String name = prompt("Enter student name: ").trim();
        if (name.isEmpty()) {
            System.out.println("Name cannot be empty.");
            return;
        }
        if (findStudent(name) != null) {
            System.out.println("A student with that name already exists. Use option 2 to update.");
            return;
        }
        Double score = readScore();
        if (score == null) return;
        students.add(new Student(name, score));
        System.out.println("Added " + name + " with score " + score + ".");
    }

    private void updateGrade() {
        Student s = selectStudent();
        if (s == null) return;
        Double score = readScore();
        if (score != null) {
            s.setScore(score);
            System.out.println("Updated " + s.getName() + "'s score to " + score + ".");
        }
    }

    private void removeStudent() {
        Student s = selectStudent();
        if (s != null) {
            students.remove(s);
            System.out.println("Removed " + s.getName() + ".");
        }
    }

    private void showStats() {
        if (students.isEmpty()) {
            System.out.println("No students to analyze.");
            return;
        }
        Student highest = students.get(0);
        Student lowest = students.get(0);
        double sum = 0;
        for (Student s : students) {
            sum += s.getScore();
            if (s.getScore() > highest.getScore()) highest = s;
            if (s.getScore() < lowest.getScore()) lowest = s;
        }
        System.out.println("--- Statistics ---");
        System.out.printf("Number of students : %d%n", students.size());
        System.out.printf("Average score      : %.2f%n", sum / students.size());
        System.out.printf("Highest score      : %.2f (%s)%n", highest.getScore(), highest.getName());
        System.out.printf("Lowest score       : %.2f (%s)%n", lowest.getScore(), lowest.getName());
    }

    private void showReport() {
        if (students.isEmpty()) {
            System.out.println("No students to report.");
            return;
        }
        System.out.println("=== Summary Report ===");
        System.out.printf("%-20s %10s%n", "Name", "Score");
        System.out.println("-------------------- ----------");
        for (Student s : students) {
            System.out.printf("%-20s %10.2f%n", s.getName(), s.getScore());
        }
        System.out.println("-------------------- ----------");
        System.out.printf("Total students: %d%n", students.size());
    }

    private void sortByName() {
        if (students.isEmpty()) {
            System.out.println("No students to sort.");
            return;
        }
        students.sort(Comparator.comparing(Student::getName, String.CASE_INSENSITIVE_ORDER));
        System.out.println("Students sorted by name.");
    }

    private void sortByScore() {
        if (students.isEmpty()) {
            System.out.println("No students to sort.");
            return;
        }
        Collections.sort(students, Comparator.comparingDouble(Student::getScore).reversed());
        System.out.println("Students sorted by score (descending).");
    }

    // ----- Helpers ---------------------------------------------------------

    private Student selectStudent() {
        if (students.isEmpty()) {
            System.out.println("No students available.");
            return null;
        }
        Student found = findStudent(prompt("Enter student name: ").trim());
        if (found == null) {
            System.out.println("Student not found.");
        }
        return found;
    }

    private Student findStudent(String name) {
        for (Student s : students) {
            if (s.getName().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    private Double readScore() {
        System.out.printf("Enter score (%.1f - %.1f): ", MIN_SCORE, MAX_SCORE);
        String line = scanner.nextLine().trim();
        try {
            double score = Double.parseDouble(line);
            if (score < MIN_SCORE || score > MAX_SCORE) {
                System.out.println("Score must be between " + MIN_SCORE + " and " + MAX_SCORE + ".");
                return null;
            }
            return score;
        } catch (NumberFormatException e) {
            System.out.println("Invalid number: " + line);
            return null;
        }
    }

    private String prompt(String message) {
        System.out.print(message);
        return scanner.nextLine();
    }
}
