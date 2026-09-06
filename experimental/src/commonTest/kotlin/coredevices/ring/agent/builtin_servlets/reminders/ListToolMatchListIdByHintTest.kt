@file:OptIn(ExperimentalTime::class)

package coredevices.ring.agent.builtin_servlets.reminders

import coredevices.ring.agent.builtin_servlets.reminders.ListTool.Companion.matchListIdByHint
import coredevices.ring.agent.builtin_servlets.reminders.ListTool.Companion.destinationHintForRequest
import coredevices.ring.data.entity.room.indexfeed.CachedList
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.LIST_NOTES_SELF_ID
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.LIST_SHOPPING_ID
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.LIST_TODOS_ID
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.SEED_NOTES_SELF
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.SEED_SHOPPING
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.SEED_TODOS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime

class ListToolMatchListIdByHintTest {

    private val seededLists = listOf(
        CachedList(firestoreId = LIST_NOTES_SELF_ID, title = "Notes to self", seed = SEED_NOTES_SELF),
        // The Todos list is renamed to "Reminders" but keeps its "todos" seed.
        CachedList(firestoreId = LIST_TODOS_ID, title = "Reminders", seed = SEED_TODOS),
        CachedList(firestoreId = LIST_SHOPPING_ID, title = "Shopping list", seed = SEED_SHOPPING),
    )

    @Test
    fun todoHintResolvesToRenamedRemindersListViaSeed() {
        // The model is still prompted to use 'todo'; the renamed title no longer
        // contains it, so resolution must fall back to the unchanged seed.
        assertEquals(LIST_TODOS_ID, matchListIdByHint(seededLists, "todo"))
    }

    @Test
    fun reminderHintResolvesToRenamedRemindersListViaTitle() {
        assertEquals(LIST_TODOS_ID, matchListIdByHint(seededLists, "reminder"))
    }

    @Test
    fun shoppingHintResolvesViaTitle() {
        assertEquals(LIST_SHOPPING_ID, matchListIdByHint(seededLists, "shopping"))
    }

    @Test
    fun titleMatchIsPreferredOverSeedMatch() {
        // A user-created list whose title contains the hint should win over a
        // built-in list that only matches on seed.
        val lists = seededLists + CachedList(firestoreId = "custom", title = "My todo backlog", seed = null)
        assertEquals("custom", matchListIdByHint(lists, "todo"))
    }

    @Test
    fun groceryHintResolvesToShoppingListViaSynonym() {
        assertEquals(LIST_SHOPPING_ID, matchListIdByHint(seededLists, "grocery"))
        assertEquals(LIST_SHOPPING_ID, matchListIdByHint(seededLists, "groceries"))
        assertEquals(LIST_SHOPPING_ID, matchListIdByHint(seededLists, "groceries for the week"))
    }

    @Test
    fun userCreatedGroceryListWinsOverSynonym() {
        val lists = seededLists + CachedList(firestoreId = "custom", title = "Grocery", seed = null)
        assertEquals("custom", matchListIdByHint(lists, "grocery"))
        // Plural/singular mismatch with the custom title must still prefer it over Shopping.
        assertEquals("custom", matchListIdByHint(lists, "groceries"))
        assertEquals("custom", matchListIdByHint(lists, "groceries for the week"))
    }

    @Test
    fun userCreatedGroceriesListWinsOverSynonymForSingularHint() {
        val lists = seededLists + CachedList(firestoreId = "custom", title = "Groceries", seed = null)
        assertEquals("custom", matchListIdByHint(lists, "grocery"))
    }

    @Test
    fun exactCustomNoteTitleAndBuiltInDestinationsRemainResolvable() {
        val lists = seededLists + CachedList(firestoreId = "today-note", title = "Today note")
        assertEquals("today-note", matchListIdByHint(lists, "Today note"))
        assertEquals(LIST_NOTES_SELF_ID, matchListIdByHint(lists, "Notes to self"))
        assertEquals(LIST_TODOS_ID, matchListIdByHint(lists, "Reminders"))
        assertEquals(LIST_SHOPPING_ID, matchListIdByHint(lists, "Shopping"))
    }

    @Test
    fun explicitCustomTitleInTranscriptOverridesIncorrectModelHint() {
        val lists = seededLists + CachedList(firestoreId = "today-note", title = "Today note")
        assertEquals(
            "Today note",
            destinationHintForRequest(
                lists,
                modelHint = "shopping",
                userMessage = "Add something to my Today Note: Today is not raining.",
            ),
        )
    }

    @Test
    fun reminderWordingOverridesIncorrectShoppingHint() {
        listOf(
            "Remind me to call the bank",
            "A reminder, I need to go pick up Illiana's mark.",
            "And a reminder, I need to go pick up my niece tomorrow.",
            "Add a reminder, add",
        ).forEach { request ->
            assertEquals(
                "todo",
                destinationHintForRequest(
                    seededLists,
                    modelHint = "shopping",
                    userMessage = request,
                ),
                request,
            )
        }
    }

    @Test
    fun explicitNamedListStillWinsWhenSavedContentMentionsReminder() {
        assertEquals(
            "Shopping list",
            destinationHintForRequest(
                seededLists,
                modelHint = "shopping",
                userMessage = "Add birthday reminder cards to my Shopping list",
            ),
        )
    }

    @Test
    fun modelHintRemainsFallbackWhenTranscriptHasNoDestinationEvidence() {
        assertEquals(
            "work",
            destinationHintForRequest(
                seededLists,
                modelHint = "work",
                userMessage = "Add the quarterly report",
            ),
        )
    }

    @Test
    fun unknownHintResolvesToNull() {
        assertNull(matchListIdByHint(seededLists, "vacation packing"))
    }

    @Test
    fun blankHintResolvesToNull() {
        assertNull(matchListIdByHint(seededLists, "   "))
    }
}
