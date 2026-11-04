# CodeAlpha_ProjectName

Author : Oishee Biswas

This repository contains four independent projects developed as part of the CodeAlpha Internship. These projects demonstrate practical programming concepts through applications involving artificial intelligence, hotel booking, stock trading, and student grade management.

Projects Overview

<h2> 1. Artificial Intelligence Chatbot </h2>

<strong>Folder:</strong> codealpha_tasks/CodeAlpha_Artificial_Intelligence_Chatbot/

<p> The Artificial Intelligence Chatbot is a Java-based conversational application designed to understand user questions and provide relevant responses using Natural Language Processing (NLP) techniques. </p>

<h4>Key Features:</h4>
<ul>
  <li>Browser-based chat interface.
</li>
  <li>FAQ knowledge base organised by topics or intents.</li>
  <li>Text processing and stemming to interpret different forms of words.</li>
  <li>Naive Bayes classification to identify the likely intent of a question.</li>
  <li>TF-IDF-based text similarity and retrieval to find relevant FAQ responses.</li>
  <li>Related-question suggestions and response confidence information.
</li>
  <li>Command-line options to train the model, ask questions, run self-tests, or start the web server.</li>
</ul>

<h4>Technologies Used:</h4>
<ul>
  <li>Java</li>
  <li>Natural Language Processing (NLP)</li>
  <li>Multinomial Naive Bayes</li>
  <li>TF-IDF text retrieval</li>
  <li>HTML, CSS and JavaScript</li>
</ul>

<h4>How to Run:</h4>
<h5>Open a terminal in the chatbot project folder :</h5> cd codealpha_tasks/CodeAlpha_Artificial_Intelligence_Chatbot <br>
<h5>Compile the Java source files :</h5> bash build.sh <br>
<h5>Start the chatbot :</h5> java -cp out chatbot.Main <br>
<h5>Open http://localhost :</h5> 8080 in your browser.

<h4>Other available commands:</h4>
java -cp out chatbot.Main --train <br>
java -cp out chatbot.Main --ask "your question here" <br>
java -cp out chatbot.Main --selftest <br>
java -cp out chatbot.Main --port 9090

<h3>Requirement: Java Development Kit (JDK).</h3>

<h2> 2. Hotel Reservation System </h2>

<strong>Folder:</strong> codealpha_tasks/CodeAlpha_Hotel_Reservation/

<p>The Hotel Reservation System is a Python application that simulates the reservation process of a hotel. Users can search for available rooms, make bookings, view reservation details, and cancel reservations.</p>

<h4>Key Features:</h4>
<ul>
  <li>Search rooms by check-in/check-out dates, room category, number of guests, and budget.</li>
  <li>Support for Standard, Deluxe, and Suite room categories.</li>
  <li>Check room availability and prevent overlapping confirmed reservations.</li>
  <li>Create reservations with guest details.</li>
  <li>Simulated card and cash payment options.</li>
  <li>Apply cancellation rules and calculate refunds.</li>
  <li>Find reservations using a guest's email address.</li>
  <li>Generate occupancy and revenue reports.</li>
  <li>Store room and reservation information in JSON files so records remain available after restarting the application.</li>
</ul>

<h4>Technologies Used:</h4>
<ul>
  <li>Python 3.10+</li>
  <li>Object-Oriented Programming (OOP)</li>
  <li>JSON-based file storage</li>
  <li>Python standard library</li>
</ul>

<h4>How to Run:</h4>
<h5>Open a terminal in the hotel reservation project folder :</h5> cd codealpha_tasks/CodeAlpha_Hotel_Reservation <br>
python main.py

The application opens an interactive console menu. On its first run, it creates the demo room inventory in the data directory.

<h5>To run the test suite :</h5> python -m unittest discover -s tests -v

<h3>Requirement: Python 3.10 or later. No third-party Python packages are required.</h3>

<em>Note: The payment gateway is simulated for demonstration purposes; it does not process real payments.</em>

<h2> 3. Stock Trading Platform </h2>

<strong>Folder:</strong> codealpha_tasks/CodeAlpha_Stock_Trading_Platform/

<p>The Stock Trading Platform is a web-based stock-market simulation. Users can create trading accounts, buy and sell shares using virtual cash, and track their portfolios as simulated stock prices change over time.</p>

<h4>Key Features:</h4>
<ul>
  <li>Simulated market containing a selection of stocks.</li>
  <li>Stock prices that update automatically at regular intervals.</li>
  <li>Create and manage user trading accounts.</li>
  <li>Buy and sell shares with order validation.</li>
  <li>Check available cash and prevent purchases or sales that exceed account limits.</li>
  <li>Track cash balance, stock holdings, and portfolio value.</li>
  <li>View transaction history and realised profit/loss.</li>
  <li>Record portfolio performance snapshots over time.</li>
  <li>Store users, stock data, holdings, transactions, and performance history in an SQLite database.</li>
  <li>Browser-based dashboard served by a Node.js HTTP server.</li>
</ul>

<h4>Technologies Used:</h4>
<ul>
  <li>JavaScript (ES modules)</li>
  <li>Node.js</li>
  <li>SQLite</li>
  <li>HTML, CSS and JavaScript</li>
</ul>

<h4>How to Run:</h4>
<h5>Open a terminal in the stock trading project folder :</h5> cd codealpha_tasks/CodeAlpha_Stock_Trading_Platform <br>
npm start

Then open the following address in your browser : http://localhost:3000

The SQLite database is created in the project's data directory when the application starts.

<h3>Requirement: Node.js 22.5 or later versions.</h3>

<em>Note: This is a stock-trading simulator. It uses simulated market prices and virtual balances; it is not connected to a real stock exchange and does not place real trades.</em>

<h2> 4. Student Grade Tracker </h2>

<strong>Folder:</strong> codealpha_tasks/CodeAlpha_Student_Grade_Tracker/

<p>The Student Grade Tracker helps organise student marks and review academic performance. The project includes a Java console-based grade manager and a browser-based interface.</p>

<h4>Key Features:</h4>
<ul>
  <li>Add students and record their scores.</li>
  <li>Update a student's score or remove a student.</li>
  <li>Validate scores within the range of 0 to 100 in the Java console application.</li>
  <li>Calculate the class average, highest score, and lowest score.</li>
  <li>Display a summary report of students and their scores.</li>
  <li>Sort students alphabetically or by score.</li>
  <li>Browser interface with grade summaries and a grading-scale display.</li>
  <li>Responsive layout and light/dark theme options in the browser interface.</li>
</ul>

<h4>Technologies Used:</h4>
<ul>
  <li>Java</li>
  <li>ArrayList and Java Collections</li>
  <li>HTML, CSS and JavaScript</li>
</ul>

<h4>How to Run:</h4>
<h5>Option 1: Run the browser interface</h5>
Open codealpha_tasks/CodeAlpha_Student_Grade_Tracker/index.html in a web browser.

<h5>Option 2: Run the Java console application</h5>
Open a terminal in the project folder :
cd codealpha_tasks/CodeAlpha_Student_Grade_Tracker <br>
javac Student.java GradeManager.java <br>
java GradeManager

<h3>Requirement for the Java version: Java Development Kit (JDK).</h3>
