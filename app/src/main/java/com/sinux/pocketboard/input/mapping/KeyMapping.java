private final KeyMappingValue[] keyMappingValues;
private final boolean hasAdditionalValues;

private final KeyMappingValue[] keyMappingAltValues;
private final boolean hasAltValues;
private final boolean hasAdditionalAltValues;

private final int doublePressValue;
private final int doublePressShiftValue;

public KeyMapping(
        KeyMappingValue[] keyMappingValues,
        KeyMappingValue[] keyMappingAltValues,
        int doublePressValue,
        int doublePressShiftValue) {

    if (keyMappingValues == null
            || keyMappingValues.length == 0) {
        throw new IllegalArgumentException(
                "KeyMapping must have at least one value"
        );
    }

    this.keyMappingValues = keyMappingValues;
    this.hasAdditionalValues =
            keyMappingValues.length > 1;

    this.keyMappingAltValues = keyMappingAltValues;
    this.hasAltValues =
            keyMappingAltValues != null
                    && keyMappingAltValues.length > 0;

    this.hasAdditionalAltValues =
            hasAltValues
                    && keyMappingAltValues.length > 1;

    this.doublePressValue = doublePressValue;
    this.doublePressShiftValue = doublePressShiftValue;
}

public int getValue(
        boolean shiftEnabled,
        boolean altEnabled,
        byte keyIndex) {

    if (shiftEnabled) {
        if (altEnabled) {
            return getAltShiftValue(keyIndex);
        } else {
            return getShiftValue(keyIndex);
        }
    } else {
        if (altEnabled) {
            return getAltValue(keyIndex);
        } else {
            return getValue(keyIndex);
        }
    }
}

public boolean hasAdditionalValues(
        boolean altEnabled) {

    return altEnabled
            ? hasAdditionalAltValues
            : hasAdditionalValues;
}

public boolean hasDoublePressValue() {
    return doublePressValue != 0
            || doublePressShiftValue != 0;
}

public int getDoublePressValue(
        boolean shiftEnabled) {

    if (shiftEnabled
            && doublePressShiftValue != 0) {
        return doublePressShiftValue;
    }

    return doublePressValue;
}

private int getValue(byte keyIndex) {

    int index =
            (keyIndex & 0xFF)
                    % keyMappingValues.length;

    return keyMappingValues[index].getValue();
}

private int getShiftValue(byte keyIndex) {

    int index =
            (keyIndex & 0xFF)
                    % keyMappingValues.length;

    int shiftValue =
            keyMappingValues[index].getShiftValue();

    return shiftValue != 0
            ? shiftValue
            : getValue(keyIndex);
}

private int getAltValue(byte keyIndex) {

    if (hasAltValues) {

        int index =
                (keyIndex & 0xFF)
                        % keyMappingAltValues.length;

        return keyMappingAltValues[index].getValue();
    }

    return getValue(keyIndex);
}

private int getAltShiftValue(byte keyIndex) {

    if (hasAltValues) {

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

    return getShiftValue(keyIndex);
}
