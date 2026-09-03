package com.sinux.pocketboard.spellchecker;

import android.service.textservice.SpellCheckerService;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

public class PocketBoardSpellCheckerService extends SpellCheckerService {

    @Override
    public Session createSession() {
        return new PocketBoardSpellCheckerSession();
    }

    private static class PocketBoardSpellCheckerSession
            extends SpellCheckerService.Session {

        @Override
        public void onCreate() {
            super.onCreate();
        }

        @Override
        public SuggestionsInfo onGetSuggestions(
                TextInfo textInfo,
                int suggestionsLimit) {

            return new SuggestionsInfo(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
                    new String[0]
            );
        }
    }
}
