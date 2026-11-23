package com.elepay.emtech;

/**
 * Snapshot of an ELEPAY change that the mappers turn into an Emtech object.
 * Amounts are pesewas. Places are city, region and country only.
 */
public final class RegulatoryFacts {
    private RegulatoryFacts() {}

    public record Transaction(
            String transactionId,
            String elepayKind,
            String elepayStatus,
            String channel,
            String occurredAt,
            String deviceId,
            long feePesewas,
            String feeCurrency,
            String originUserId,
            String originAccountId,
            String originInstrument,
            String originProvider,
            String originRegulatoryAccountType,
            String originCity,
            String originRegion,
            String originCountry,
            String originLocalitySource,
            long originAmountPesewas,
            String originCurrency,
            int originKycTier,
            String originKycService,
            String originKycStatus,
            String originKycType,
            String originKycTypeDetails,
            String originKycReason,
            String ghanaCardStatus,
            boolean faceApproved,
            String destinationAccountId,
            String destinationInstrument,
            String destinationProvider,
            String destinationRegulatoryAccountType,
            String destinationCity,
            String destinationRegion,
            String destinationCountry,
            String destinationLocalitySource,
            long destinationAmountPesewas,
            String destinationCurrency,
            String gatewayTransactionId,
            String gatewayProvider,
            String gatewayStatus,
            String gatewayReason,
            String reason
    ) {}

    public record Device(
            String deviceId,
            String deviceType,
            String deviceName,
            String city,
            String region,
            String country,
            String address,
            String location,
            String latitude,
            String longitude,
            String modelId,
            String manufacturer,
            String description,
            String inserviceDate,
            String connectionType,
            Integer manufactureYear
    ) {
        public boolean registrationComplete() {
            return present(deviceId) && present(deviceType) && present(city) && present(region)
                    && present(country) && present(latitude) && present(longitude)
                    && present(inserviceDate) && present(connectionType);
        }

        private static boolean present(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record DeviceEvent(
            String deviceId,
            String health,
            String status,
            String occurredAt,
            String description
    ) {}

    public record Complaint(
            String userId,
            String complaintId,
            String type,
            String typeDescription,
            String status,
            String statusAt,
            String resolution
    ) {}
}
