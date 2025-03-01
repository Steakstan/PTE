PDF to Excel Processor
A Java-based desktop application designed to extract data from PDF files and export it to Excel spreadsheets. This tool is tailored for processing PDF documents containing structured data, specifically working with order confirmations from the company BSHG. It utilizes two distinct parsing approaches and features a modern, custom Swing UI.

Features
Custom Swing UI:
Modern user interface with custom components such as rounded text fields, buttons, progress bars, and text areas for a polished look.

PDF Extraction:
Utilizes Apache PDFBox to extract text from PDF documents efficiently.

Excel Generation:
Leverages Apache POI to create and style Excel files from the parsed data, including auto-sizing columns and highlighting specific cells based on content.

Multiple Parsing Approaches:

Standard Confirmation-Based Parsing: Extracts order numbers, confirmation numbers, models, and dates from order confirmations.
Refined Second Approach: Implements advanced token merging and parsing logic for extracting detailed data (e.g., merging tokens like "KW" with date tokens).
Background Processing:
Ensures a responsive UI by running file processing tasks in the background using SwingWorker.

Modular Architecture:
Well-organized codebase separating UI, business logic, and parsing modules for maintainability and scalability.

Technologies Used
Java SE: Core language for application development.
Swing: For building the graphical user interface.
Apache PDFBox: For PDF text extraction.
Apache POI: For creating and manipulating Excel files.
Regular Expressions: For pattern matching and data validation.
Java Concurrency (SwingWorker): To perform background processing.
How It Works
User Input:
The user selects a directory containing PDF files and specifies the output Excel file location.

PDF Processing:
The application extracts text from each PDF file and processes it using one of the two available parsing approaches, with a focus on handling order confirmations from BSHG.

Data Parsing:
Extracted information such as order numbers, model names, confirmation numbers, and dates is structured into rows.

Excel Export:
The parsed data is exported to an Excel file with proper styling and formatting.

User Feedback:
A progress bar and log area provide real-time feedback during the processing phase.

Getting Started
Prerequisites
Java 8 or higher
Maven/Gradle for dependency management (if applicable)
Apache PDFBox and Apache POI libraries
Running the Application
Clone the repository:

bash
Copy
git clone https://github.com/Steakstan/PTE.git
Build the project using your preferred build tool:

bash
Copy
mvn clean install
or

bash
Copy
gradle build
Run the application:

bash
Copy
java -jar target/pdf-to-excel-processor.jar
Contribution
Contributions are welcome! Please feel free to submit issues and pull requests for improvements and additional features.

This program demonstrates a robust approach to integrating document processing and UI development in Java, showcasing skills in both backend and frontend development, with a particular focus on handling order confirmations from BSHG. Enjoy using the PDF to Excel Processor!
