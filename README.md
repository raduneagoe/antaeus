## Antaeus

Antaeus (/ænˈtiːəs/), in Greek mythology, a giant of Libya, the son of the sea god Poseidon and the Earth goddess Gaia. He compelled all strangers who were passing through the country to wrestle with him. Whenever Antaeus touched the Earth (his mother), his strength was renewed, so that even if thrown to the ground, he was invincible. Heracles, in combat with him, discovered the source of his strength and, lifting him up from Earth, crushed him to death.

Welcome to our challenge.

## The challenge

As most "Software as a Service" (SaaS) companies, Pleo needs to charge a subscription fee every month. Our database contains a few invoices for the different markets in which we operate. Your task is to build the logic that will schedule payment of those invoices on the first of the month. While this may seem simple, there is space for some decisions to be taken and you will be expected to justify them.

## Instructions

Fork this repo with your solution. Ideally, we'd like to see your progression through commits, and don't forget to update the README.md to explain your thought process.

Please let us know how long the challenge takes you. We're not looking for how speedy or lengthy you are. It's just really to give us a clearer idea of what you've produced in the time you decided to take. Feel free to go as big or as small as you want.

## Solution

I've started this challenge by taking small steps and figuring out what are the key features in order to have a reliable billing service, but also keep the changes to the original code as minimal as possible.

In order for the billing service to charge the customers, it needs to get the pending invoices from the database. New methods were introduced in the `InvoiceService` class to help fetch the invoices by status and also update the status after a bill will be paid. Since I am new to Kotlin, changes were also made to the REST API to easily check that the methods are working as intended.

When I saw the `PaymentProvider` interface, why it returns `true`/`false` and what are the exception it throws, it made me think about what would be the consequences. Since the billing service charges customers to activate their subscription, the `Customer` Table needed a `hasSubscription` field to see if the customer has a valid subscription and what happens to it after the `PaymentProvider` tries to charge the pending invoice. I updated the `Customers` table accordingly and added `updateHasSubscription` method to `CustomerService` to update its value in the database.

Next, I added the basic functionality inside the `BillingService`, where the `schedulePaymentOfPendingInvoices` schedules a task every day, and checks if it's the first day of the month in order to call `settleInvoices` with the pending invoices as a parameter. The `settleInvoices` calls the `charge` method of the `PaymentProvider` and takes action based on it's answer. If the operation succeeds, then the `hasSubscription` is set to `true`, otherwise is set to `false`. I'll cover the exception cases later on.
Also in order to not charge the customer for a paid invoice, `settleInvoices` checks invoices status before taking any action.

Since the payment might fail, I thought it might be a good idea to inform the customer about the status of its subscription, so I added a really basic `NotificationService` that in theory it sends a notification to the customer id with the specified message.

Also added logging for Pleo engineers to easily check what happened especially in case of error.

`CurrencyConverterService` was also added to be later used when a `CurrencyMismatchException` occurs. The idea is that it converts value from one currency to the other contacting external API. Since it does an external call, I was thinking that this might throw at least a `NetworkException`.

Coming back to the `charge` method of the `PaymentProvider` and the failures that might happen, there are 4 cases:
1. Insufficient funds
1. CustomerNotFoundException
1. CurrencyMismatchException
1. NetworkException

I'll start by covering the `NetworkException` since the retry mechanism introduced by this case also influences the others. If this exception occurs, the subscription is canceled, and the customer is notified about the problem and that he could try to trigger the payment process manually, but wouldn't know if the `NetworkException` is fixed by the backend, so it might still fail.
A retry mechanism was put in place by adding a retry count field called `retry` in the `Invoice` table. This field is increased on `NetworkException` and it will be automatically retried by the `schedulePaymentOfPendingInvoices` that now also checks every day only the pending invoices that failed in the past (with a retry number greater than 0). If the invoice is retried to many times, it's not going to be picked up again, until the beginning of the month.

Insufficient funds is a customer issue and there is nothing to do on the backend side, besides notifying the customer, removing its subscription and resetting the retry number of the invoice to 0.

In case of a `CurrencyMismatchException`, it converts the invoice amount to the customer currency and tries again to settle the invoice. In case of a `NetworkException` from the `CurrencyConverterService`, has the same behaviour as above, but different notification message for the customer to inform him that he could try to change his currency and retry manually if he doesn’t want to wait for the automatic retry.

`CustomerNotFoundException` just logs the exception and leaves the invoice in a pending state in case the customer creates back his account in the future.

### Confusion

One thing that made me confuse was that the payment provider is an external service, and I didn't understand from where does it extract the money. From a credit card or from the customers account? If it's an external service, I thought it might be from a credit card, but then why would the Customer table have a currency field? If it's extracting money from the customers account, then it should have a balance field, but I wasn't sure about all this and decide to skip it.

### Could have done differently

I chose to modify the Invoice table with the retry number and keep it there for durability reasons, in case the App crashes. I could have also just keep everything in memory, unsubscribe the customer, retry a few times (even faster than 1 per day), and then notify the customer when the retry number exceeds the threshold, but if the app crashed the customer wouldn't be notified.
Also, could have unsubscribed the customer after the retry number exceeds the threshold, but the retry should have happened faster in order to not keep the customer with an active subscription for too long.

Regarding the payment on the first day of the month, I could have probably used a library to schedule them, since `Timer().scheduleAtFixedRate()` depends on when the app was started, doesn't take in to account different timezones and changes in time like summer/winter hour.

### End thought

Thank you for taking the time and considering me for this opportunity. The challenge was really fun, and I really liked playing with a new programming language. I hope you enjoyed reading about my solution, and I am curious about the feedback!

## Developing

Requirements:
- \>= Java 11 environment

Open the project using your favorite text editor. If you are using IntelliJ, you can open the `build.gradle.kts` file and it is gonna setup the project in the IDE for you.

### Building

```
./gradlew build
```

### Running

There are 2 options for running Anteus. You either need libsqlite3 or docker. Docker is easier but requires some docker knowledge. We do recommend docker though.

*Running Natively*

Native java with sqlite (requires libsqlite3):

If you use homebrew on MacOS `brew install sqlite`.

```
./gradlew run
```

*Running through docker*

Install docker for your platform

```
docker build -t antaeus
docker run antaeus
```

### App Structure
The code given is structured as follows. Feel free however to modify the structure to fit your needs.
```
├── buildSrc
|  | gradle build scripts and project wide dependency declarations
|  └ src/main/kotlin/utils.kt 
|      Dependencies
|
├── pleo-antaeus-app
|       main() & initialization
|
├── pleo-antaeus-core
|       This is probably where you will introduce most of your new code.
|       Pay attention to the PaymentProvider and BillingService class.
|
├── pleo-antaeus-data
|       Module interfacing with the database. Contains the database 
|       models, mappings and access layer.
|
├── pleo-antaeus-models
|       Definition of the Internal and API models used throughout the
|       application.
|
└── pleo-antaeus-rest
        Entry point for HTTP REST API. This is where the routes are defined.
```

### Main Libraries and dependencies
* [Exposed](https://github.com/JetBrains/Exposed) - DSL for type-safe SQL
* [Javalin](https://javalin.io/) - Simple web framework (for REST)
* [kotlin-logging](https://github.com/MicroUtils/kotlin-logging) - Simple logging framework for Kotlin
* [JUnit 5](https://junit.org/junit5/) - Testing framework
* [Mockk](https://mockk.io/) - Mocking library
* [Sqlite3](https://sqlite.org/index.html) - Database storage engine

Happy hacking 😁!
