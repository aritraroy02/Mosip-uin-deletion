# MOSIP UIN Registration & Voluntary Deletion Portal

This project is a Spring Boot web application designed as a light-themed MOSIP-style identity portal. It integrates a **dual-database architecture** to isolate demographic records from cryptographic identity keys (UIN hashes), and includes REST APIs as well as an interactive web interface for user registration and voluntary data purging.

---

## 🏗️ Architecture & Data Flow

To ensure data security, user records are divided and stored on two separate cloud-hosted PostgreSQL databases:

1. **Basic Details Database** (`defaultdb` on host `:20760`): Stores only public-facing demographic info (`user_id`, `name`, `phone`).
2. **Hashing Database** (`defaultdb` on host `:24845`): Stores security mapping data (`user_id`, `hashed_uin` via SHA-256).

```mermaid
flowchart TD
    subgraph Frontend [Web Interface]
        Home["Homepage (/)"] --> Register["Registration (/register)"]
        Home --> DeletePortal["Data Purge (/delete)"]
    end

    subgraph ControllerLayer [Controllers]
        MVC[RegistrationController]
        API[RegistrationApiController]
    end

    subgraph Databases [Data Repositories]
        DB_Basic[("Basic Database (Name, Phone)")]
        DB_Hash[("Hashing Database (User ID, Hashed UIN)")]
    end

    Register -->|Web POST| MVC
    DeletePortal -->|Web POST| MVC
    
    MVC -->|Persist Basic Details| DB_Basic
    MVC -->|Persist SHA-256 UIN| DB_Hash

    API -->|POST /api/register| DB_Basic
    API -->|POST /api/register| DB_Hash
    API -->|DELETE /api/user/{id}| DB_Basic
    API -->|DELETE /api/user/{id}| DB_Hash
```

---

## 📂 File Structure & Descriptions

```
src/main/java/com/example/mosip/
│
├── config/
│   ├── BasicDbConfig.java     # Configures Primary Datasource (Database 1) & scans basic packages.
│   └── HashingDbConfig.java   # Configures Secondary Datasource (Database 2) & scans hashing packages.
│
├── controller/
│   ├── RegistrationController.java    # Handles Web views & Forms (/, /register, /delete).
│   └── api/
│       └── RegistrationApiController.java  # Exposes single/bulk registration & deletion REST APIs.
│
├── dto/
│   └── UserRegistrationDto.java   # Data Transfer Object for registration form payload.
│
├── entity/
│   ├── basic/
│   │   └── UserBasicDetails.java   # JPA entity representing table 'user_basic_details' in Database 1.
│   └── hashing/
│       └── UserUinHash.java        # JPA entity representing table 'user_uin_hash' in Database 2.
│
├── repository/
│   ├── basic/
│   │   └── UserBasicDetailsRepository.java  # JPA repository for Database 1 operations.
│   └── hashing/
│       └── UserUinHashRepository.java       # JPA repository for Database 2 operations.
│
└── util/
    └── HashUtils.java              # Helper class executing SHA-256 hex digests.
```

---

## 🚀 Running the Application

### Prerequisites
The project includes a portable Eclipse Temurin JDK 17 setup under `.jdks` in the project root. You do not need to install Java globally.

### Start the Server
Run the following commands in PowerShell from the project root directory:

```powershell
# Set portable Java Environment
$env:JAVA_HOME = "$pwd\.jdks\jdk-17.0.19+10"

# Compile and start Tomcat server
.\mvnw spring-boot:run
```

Once started, the application will be available at:
* **Web UI Portal**: [http://localhost:8080](http://localhost:8080)
* **REST APIs**: `http://localhost:8080/api/...`

---

## 📡 REST API Documentation

### 1. Register User (Single)
* **Endpoint**: `POST /api/register`
* **Content-Type**: `application/json`
* **Request Body**:
  ```json
  {
    "name": "Aritraditya Roy",
    "phone": "+919999988888",
    "email": "aritr@example.com",
    "dob": "2000-01-01",
    "nationality": "Indian",
    "consent": true,
    "uin": "1234567890"
  }
  ```
* **Response**:
  ```json
  {
    "userId": "USR-E73CE10B",
    "name": "Aritraditya Roy",
    "phone": "+919999988888",
    "email": "aritr@example.com",
    "dob": "2000-01-01",
    "nationality": "Indian",
    "consent": true,
    "uin": "1234567890",
    "hashedUin": "c775e7b757ede630cd0aa1113bd102661ab38829ca52a6422ab782862f268646",
    "status": "SUCCESS"
  }
  ```

### 2. Register Users (Bulk)
* **Endpoint**: `POST /api/register/bulk`
* **Content-Type**: `application/json`
* **Request Body**: A JSON array of single registration payloads.

### 3. Delete User Data (Unified Purge)
* **Endpoint**: `DELETE /api/user/{userId}`
* **Description**: Purges demographic details from Database 1 and the UIN hash key from Database 2.
* **Response (Success)**:
  ```json
  {
    "message": "User details and UIN hash successfully deleted from all databases.",
    "userId": "USR-E73CE10B",
    "status": "DELETED"
  }
  ```
* **Response (Not Found)**: Returns `404 Not Found` if the User ID does not exist in any database.

---

## 🗑️ Voluntary Data Deletion UI
Residents can click on **"Delete my data"** located in the page footer (at any view).
1. The link navigates to `/delete`.
2. The resident inputs their unique **User ID** (`USR-XXXXXXXX`).
3. Upon confirming consent and submitting the form, the controller runs a unified delete, removing files/records from both cloud databases simultaneously and displaying a success notification.
