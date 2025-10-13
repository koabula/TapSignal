## Summary
10.10

Added a module called "tap (transport as plugin)" in Signal, changing the transmission of Signal ciphertext from Signal Server to a user-configured provider. Used in the following cases:

#### 1. Private chat:  
A initiates "use tap mode", generates a read-only token for A’s configured cloud_A, and sends it to B.  
After B accepts the request, B also sends a read-only token (of cloud_B) to A.  
Then, tap mode is activated:  
A sends messages by uploading ciphertext to cloud_A.  
B periodically pulls ciphertext from cloud_A and decrypts it.
Similarly, B can send messages via Cloud B, and A pulls from Cloud B using token_B

#### 2. Group chat:  
A initiates "use tap mode", generates a read-only token for A’s configured cloud_A, and sends it to all members. The group enters “Proposing” state.  
After receiving the request, B agrees and sends a read-only token for cloud_B to all members.  
...  
Once all members’ tokens are locally available, the group enters tap mode.  
A sends messages by uploading ciphertext to cloud_A.  
A periodically pulls ciphertext from other members’ clouds, then decrypts them.

---

## Diagrams

#### 1. Private chat:  

```mermaid
sequenceDiagram
    participant A as User A
    participant CloudA as Cloud A
    participant B as User B
    participant CloudB as Cloud B
    
    Note over A,B: Tap Mode Initialization
    A->>A: Initiate "use tap mode"
    A->>CloudA: Generate read-only token
    CloudA-->>A: Return token_A
    A->>B: Send tap mode request + token_A
    
    B->>B: Accept tap mode request
    B->>CloudB: Generate read-only token
    CloudB-->>B: Return token_B
    B->>A: Send token_B
    
    Note over A,B: Tap Mode Activated
    
    loop Message Exchange
        A->>A: Encrypt message
        A->>CloudA: Upload ciphertext
        B->>CloudA: Periodically pull ciphertext (using token_A)
        CloudA-->>B: Return ciphertext
        B->>B: Decrypt message
    end
    
    Note over A,B: Similarly, B can send messages via Cloud B,<br/>and A pulls from Cloud B using token_B
```

#### 2. Group chat:

```mermaid
sequenceDiagram
    participant A as User A
    participant CloudA as Cloud A
    participant B as User B
    participant CloudB as Cloud B
    participant C as User C
    participant CloudC as Cloud C
    
    Note over A,C: Tap Mode Initialization - "Proposing" State
    A->>A: Initiate "use tap mode"
    A->>CloudA: Generate read-only token
    CloudA-->>A: Return token_A
    A->>B: Send tap mode request + token_A (to all members)
    A->>C: Send tap mode request + token_A (to all members)
    
    Note over A,C: Group State: Proposing
    
    B->>B: Accept tap mode request
    B->>CloudB: Generate read-only token
    CloudB-->>B: Return token_B
    B->>A: Send token_B (to all members)
    B->>C: Send token_B (to all members)
    
    C->>C: Accept tap mode request
    C->>CloudC: Generate read-only token
    CloudC-->>C: Return token_C
    C->>A: Send token_C (to all members)
    C->>B: Send token_C (to all members)
    
    Note over A,C: All tokens locally available
    Note over A,C: Group State: Tap Mode Activated
    
    loop Message Exchange
        A->>A: Encrypt message
        A->>CloudA: Upload ciphertext
        
        par B pulls from A's cloud
            B->>CloudA: Periodically pull (using token_A)
            CloudA-->>B: Return ciphertext
            B->>B: Decrypt message
        and C pulls from A's cloud
            C->>CloudA: Periodically pull (using token_A)
            CloudA-->>C: Return ciphertext
            C->>C: Decrypt message
        end
    end
    
    Note over A,C: Each member uploads to their own cloud,<br/>and pulls from other members' clouds using their tokens
```

---

## Some notes

1. An abstract class has been added; the program no longer directly depends on Tencent or AWS services. Additional storage providers can be added in the future.

2. Only the transport layer was changed; the encryption feature remains unchanged.

3. One finding: Signal’s two-person chat still uses Double Ratchet encryption, not Sender Key as used in other group chats.