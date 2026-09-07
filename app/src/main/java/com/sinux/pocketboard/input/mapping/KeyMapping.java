package com.sinux.pocketboard.input.mapping;

public final class KeyMapping {

    private final KeyMappingValue[] keyMappingValues;
    private final boolean hasAdditionalValues;

    private final KeyMappingValue[] keyMappingAltValues;
    private final boolean hasAltValues;
    private final boolean hasAdditionalAltValues;

    public KeyMapping(
            KeyMappingValue[] keyMappingValues,
            KeyMappingValue[] keyMappingAltValues) {

        if (keyMappingValues == null
                || keyMappingValues.length == 0) {
            throw new IllegalArgumentException(
                    "KeyMapping must have at least one value"
            );
        }

        this.keyMappingValues =
                keyMappingValues;

        hasAdditionalValues =
                keyMappingValues.length > 1;

        this.keyMappingAltValues =
                keyMappingAltValues;

        hasAltValues =
                keyMappingAltValues != null
                        && keyMappingAltValues.length > 0;

        hasAdditionalAltValues =
                hasAltValues
                        && keyMappingAltValues.length > 1;
    }

    public int getValue(
            boolean shiftEnabled,
            boolean altEnabled,
            byte keyIndex) {

        if (shiftEnabled) {

            if (altEnabled) {
                return getAltShiftValue(keyIndex);
            }

            return getShiftValue(keyIndex);
        }

        if (altEnabled) {
            return getAltValue(keyIndex);
        }

        return getValue(keyIndex);
    }

    public boolean hasAdditionalValues(
            boolean altEnabled) {

        return altEnabled
                ? hasAdditionalAltValues
                : hasAdditionalValues;
    }

    public boolean hasAltValues() {
        return hasAltValues;
    }

    public int getAltValueCount() {
        if (!hasAltValues) {
            return 0;
        }

        return keyMappingAltValues.length;
    }

    private int getValue(byte keyIndex) {

        int index =
                (keyIndex & 0xFF)
                        % keyMappingValues.length;

        return keyMappingValues[index]
                .getValue();
    }

    private int getShiftValue(byte keyIndex) {

        int index =
                (keyIndex & 0xFF)
                        % keyMappingValues.length;

        int shiftValue =
                keyMappingValues[index]
                        .getShiftValue();

        return shiftValue != 0
                ? shiftValue
                : getValue(keyIndex);
    }

    private int getAltValue(byte keyIndex) {

        if (!hasAltValues) {
            return getValue(keyIndex);
        }

        int index =
                (keyIndex & 0xFF)
                        % keyMappingAltValues.length;

        return keyMappingAltValues[index]
                .getValue();
    }

    private int getAltShiftValue(byte keyIndex) {

        if (!hasAltValues) {
            return getShiftValue(keyIndex);
        }

        int index =
                (keyIndex & 0xFF)
                        % keyMappingAltValues.length;

        int shiftValue =
                keyMappingAltValues[index]
                        .getShiftValue();

        return shiftValue != 0
                ? shiftValue
                : getAltValue(keyIndex);
    }
}
