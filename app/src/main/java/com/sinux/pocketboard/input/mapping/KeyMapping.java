package com.sinux.pocketboard.input.mapping;

public final class KeyMapping {

    private final KeyMappingValue[] keyMappingValues;
    private final boolean hasAdditionalValues;

    private final KeyMappingValue[] keyMappingAltValues;
    private final boolean hasAltValues;

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
                keyMappingAltValues != null
                        ? keyMappingAltValues
                        : new KeyMappingValue[0];

        hasAltValues =
                this.keyMappingAltValues.length > 0;
    }

    /**
     * Returns the character for the requested layer/index.
     *
     * Normal layer:
     *   index 0 = normal character
     *   index 1+ = multipress characters
     *
     * ALT layer:
     *   index 0 = physical ALT character
     *
     * ALT values are completely independent from multipress values.
     */
    public int getValue(
            boolean shiftEnabled,
            boolean altEnabled,
            byte keyIndex) {

        if (altEnabled) {

            if (shiftEnabled) {
                return getAltShiftValue(keyIndex);
            }

            return getAltValue(keyIndex);
        }

        if (shiftEnabled) {
            return getShiftValue(keyIndex);
        }

        return getValue(keyIndex);
    }

    /**
     * Returns whether this key has multipress values.
     */
    public boolean hasAdditionalValues(
            boolean altEnabled) {

        if (altEnabled) {
            return false;
        }

        return hasAdditionalValues;
    }

    /**
     * Returns whether this key has an ALT value.
     */
    public boolean hasAltValues() {
        return hasAltValues;
    }

    /**
     * Number of ALT values.
     *
     * For the Titan Slim this should normally be exactly 1.
     */
    public int getAltValueCount() {

        return keyMappingAltValues.length;
    }

    /**
     * Number of normal + multipress values.
     *
     * Index 0 is the normal character.
     * Index 1+ are language-specific multipress characters.
     */
    public int getValueCount() {

        return keyMappingValues.length;
    }

    /**
     * Number of multipress-only values.
     *
     * Does not include the normal character.
     */
    public int getAdditionalValueCount() {

        if (!hasAdditionalValues) {
            return 0;
        }

        return keyMappingValues.length - 1;
    }

    /**
     * Returns a multipress value directly.
     *
     * keyIndex 0 = normal character
     * keyIndex 1+ = additional/multipress character
     */
    public int getAdditionalValue(
            boolean shiftEnabled,
            byte keyIndex) {

        int index =
                (keyIndex & 0xFF)
                        % keyMappingValues.length;

        if (index == 0) {
            return getValue(keyIndex);
        }

        if (shiftEnabled) {

            int shiftValue =
                    keyMappingValues[index]
                            .getShiftValue();

            if (shiftValue != 0) {
                return shiftValue;
            }
        }

        return keyMappingValues[index]
                .getValue();
    }

    private int getValue(
            byte keyIndex) {

        int index =
                (keyIndex & 0xFF)
                        % keyMappingValues.length;

        return keyMappingValues[index]
                .getValue();
    }

    private int getShiftValue(
            byte keyIndex) {

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

    private int getAltValue(
            byte keyIndex) {

        if (!hasAltValues) {
            return getValue(keyIndex);
        }

        /*
         * ALT is intentionally independent from multipress.
         *
         * On the Titan Slim there is one physical ALT character
         * per key, so index 0 is the character printed on the key.
         */
        return keyMappingAltValues[0]
                .getValue();
    }

    private int getAltShiftValue(
            byte keyIndex) {

        if (!hasAltValues) {
            return getShiftValue(keyIndex);
        }

        int shiftValue =
                keyMappingAltValues[0]
                        .getShiftValue();

        return shiftValue != 0
                ? shiftValue
                : getAltValue(keyIndex);
    }
}
