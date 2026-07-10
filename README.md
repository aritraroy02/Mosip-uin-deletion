# MOSIP UIN Registration & Voluntary Deletion Portal

This project is a Spring Boot web application designed as a light-themed MOSIP-style identity portal. It uses a **three-database architecture** to isolate demographic records, parent details, and cryptographic identity keys (UIN hashes), stores user profile photos in a **MinIO (S3-compatible) object store**, and protects UINs with **MOSIP-style salt-modulo hashing**. It exposes both REST APIs and an interactive web interface for user registration and voluntary data purging.

---

## 🏗️ Architecture & Data Flow

To ensure data security, user records are divided across **three separate cloud-hosted PostgreSQL databases**, with profile images kept in a separate object store:

1. **Basic Details Database** (`defaultdb` on host `:20760`): Public-facing demographic info (`user_id`, `name`, `phone`).
2. **Hashing Database** (`defaultdb` on host `:24845`): Security data — the salted UIN/ID hashes (`user_uin_hash`) and the salt store (`uin_hash_salt`).
3. **Parent Details Database** (`defaultdb` on host `:12810`): Father's and mother's names (`user_id`, `father_name`, `mother_name`).
4. **MinIO Object Storage** (S3 API on `:9000`, console `:9001`): Profile photos in the `userprofilepic` bucket.

> **Single API ownership:** the parent-details write is owned solely by `RegistrationApiController`. The web form does **not** write parent data directly — it routes through that controller so all parent data passes through one API entry point.

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

    subgraph Services [Services]
        SALT[SaltModuloHashService]
        MINIO[MinioStorageService]
    end

    subgraph Databases [Data Repositories]
        DB_Basic[("Basic DB (Name, Phone)")]
        DB_Hash[("Hashing DB (ID/UIN hashes + salts)")]
        DB_Parent[("Parent DB (Father, Mother)")]
        OBJ[("MinIO bucket: userprofilepic")]
    end

    Register -->|Web POST multipart| MVC
    DeletePortal -->|Web POST| MVC

    MVC -->|Persist Basic Details| DB_Basic
    MVC -->|Salt-modulo hashes| SALT --> DB_Hash
    MVC -->|saveParentDetails| API
    MVC -->|Upload photo| MINIO --> OBJ

    API -->|POST /api/register| DB_Basic
    API -->|Salt-modulo hashes| SALT
    API -->|Persist parent details| DB_Parent
    API -->|DELETE /api/user/{id}| DB_Basic
    API -->|DELETE /api/user/{id}| DB_Hash
```

---

## 🔐 Salt-Modulo UIN Hashing (MOSIP-style)

Instead of a plain SHA-256 digest, UINs and individual IDs are protected with **salt-modulo hashing**:

```
salt  = uin_hash_salt[ id mod 1000 ]
hash  = SHA-256( id + salt )      // lowercase hex
```

- On first startup the app seeds the `uin_hash_salt` table with **1000 random salt buckets** (bucket `0..999`).
- The bucket for an identifier is `id mod 1000`, so the same ID always produces the same hash — it stays **deterministic and queryable**, while each bucket having its own random salt defeats precomputed rainbow-table attacks.
- The **raw UIN is never stored** — only its hash. UINs can be *verified* by re-hashing, but cannot be recovered/displayed later.

Two target columns are written in the `user_uin_hash` table:

| Column | Contents |
|--------|----------|
| `individual_id_hash` | salt-modulo hash of the individual ID (`USR-XXXXXXXX`) |
| `uin_salted_hash` | salt-modulo hash of the UIN |

The number of salt buckets is configurable via `mosip.ida.salt.modulo` (default `1000`).

---

## 📂 File Structure & Descriptions

```
src/main/java/com/example/mosip/
│
├── config/
│   ├── BasicDbConfig.java     # Primary Datasource (Database 1) — scans basic packages.
│   ├── HashingDbConfig.java   # Datasource (Database 2) — scans hashing packages (hashes + salts).
│   ├── ParentDbConfig.java    # Datasource (Database 3) — scans parent packages.
│   └── MinioConfig.java       # Builds the MinioClient bean from application.properties.
│
├── controller/
│   ├── RegistrationController.java    # Web views & forms (/, /register, /delete). Uploads photo,
│   │                                  #   computes salt-modulo hashes, routes parent data via the API.
│   └── api/
│       └── RegistrationApiController.java  # Single/bulk registration & deletion REST APIs.
│                                           #   Sole owner of the parent-details DB write.
│
├── dto/
│   └── UserRegistrationDto.java   # Registration form payload (incl. fatherName, motherName, profileImage).
│
├── entity/
│   ├── basic/
│   │   └── UserBasicDetails.java   # Table 'user_basic_details' in Database 1.
│   ├── hashing/
│   │   ├── UserUinHash.java        # Table 'user_uin_hash' (individual_id_hash, uin_salted_hash) in DB 2.
│   │   └── UinHashSalt.java        # Table 'uin_hash_salt' (salt buckets) in Database 2.
│   └── parent/
│       └── UserParentDetails.java  # Table 'user_parent_details' in Database 3.
│
├── repository/
│   ├── basic/
│   │   └── UserBasicDetailsRepository.java  # JPA repository for Database 1.
│   ├── hashing/
│   │   ├── UserUinHashRepository.java       # JPA repository for the hash table.
│   │   └── UinHashSaltRepository.java       # JPA repository for the salt table.
│   └── parent/
│       └── UserParentDetailsRepository.java # JPA repository for Database 3.
│
└── service/
    ├── SaltModuloHashService.java  # Seeds 1000 salt buckets; computes SHA-256(id + salt[id mod N]).
    └── MinioStorageService.java    # Auto-creates the bucket; uploads photos; builds presigned URLs.
```

---

## 🚀 Running the Application

### Prerequisites
- **JDK 17 or newer** (built and verified with Temurin/Oracle JDK 20). Set `JAVA_HOME` to point at it.
- **MinIO** running locally with a bucket named `userprofilepic`. The S3 API must be reachable on `http://127.0.0.1:9000` (the `:9001` port is only the web console). The app auto-creates the bucket on startup if it does not exist.
- Network access to the three Aiven-hosted PostgreSQL databases.

### Configuration (`src/main/resources/application.properties`)
The three datasources, the MinIO connection, and the salt modulo are configured here:

```properties
# MinIO / S3-compatible object storage (use the API port 9000, not the console 9001)
minio.endpoint=http://127.0.0.1:9000
minio.access-key=minioadmin
minio.secret-key=minioadmin
minio.bucket=userprofilepic
minio.url-expiry-seconds=604800

# MOSIP-style salt-modulo hashing — number of salt buckets
mosip.ida.salt.modulo=1000
```

> `application.properties` is git-ignored because it holds DB credentials and MinIO keys.

### Start the Server
Run from the project root:

```bash
# Point at a JDK 17+ install (example: Windows Git Bash)
export JAVA_HOME="/c/Program Files/Java/jdk-20"

# Compile and start Tomcat
./mvnw spring-boot:run
```

Or on PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-20"
.\mvnw spring-boot:run
```

Once started, the application will be available at:
* **Web UI Portal**: [http://localhost:8080](http://localhost:8080)
* **REST APIs**: `http://localhost:8080/api/...`
* **MinIO Console**: [http://127.0.0.1:9001](http://127.0.0.1:9001)

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
    "uin": "1234567890",
    "fatherName": "Richard Roy",
    "motherName": "Jane Roy"
  }
  ```
* **Response**:
  ```json
  {
    "userId": "USR-AC0A0B7C",
    "name": "Aritraditya Roy",
    "phone": "+919999988888",
    "email": "aritr@example.com",
    "dob": "2000-01-01",
    "nationality": "Indian",
    "consent": true,
    "fatherName": "Richard Roy",
    "motherName": "Jane Roy",
    "uin": "1234567890",
    "individualIdHash": "3fc2b206f34316af1d5d2dab8ab44efc4b5acd3fa9b724f2f8ab2dc0830938d0",
    "uinSaltedHash": "cf1ea746536e570ba0b3c1fda2568c599aa5fb5e0469a7af9c6a98ca33d5846a",
    "status": "SUCCESS"
  }
  ```
  > `name` and `phone` are required. If `uin` is omitted, a random 10-digit UIN is generated. The raw UIN is returned once but only its salted hash is persisted.

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
    "userId": "USR-AC0A0B7C",
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

---

## 🖼️ Profile Image Storage (MinIO)
During registration the uploaded profile photo is streamed to the MinIO `userprofilepic` bucket:
1. On startup `MinioStorageService` verifies the bucket exists and creates it if missing.
2. Each photo is stored under the key `profiles/<userId>-<uuid>.<ext>`.
3. The success page displays the image via a temporary **presigned URL** (validity set by `minio.url-expiry-seconds`), so the bucket does not need to be made public.
