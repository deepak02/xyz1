package de.danoeh.antennapod.activity;

import android.annotation.TargetApi;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.Validate;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.adapter.NavListAdapter;
import de.danoeh.antennapod.core.asynctask.FeedRemover;
import de.danoeh.antennapod.core.asynctask.TempFeedRemover;
import de.danoeh.antennapod.core.dialog.ConfirmationDialog;
import de.danoeh.antennapod.core.event.MessageEvent;
import de.danoeh.antennapod.core.event.ProgressEvent;
import de.danoeh.antennapod.core.event.QueueEvent;
import de.danoeh.antennapod.core.feed.EventDistributor;
import de.danoeh.antennapod.core.feed.Feed;
import de.danoeh.antennapod.core.feed.FeedMedia;
import de.danoeh.antennapod.core.preferences.PlaybackPreferences;
import de.danoeh.antennapod.core.preferences.UserPreferences;
import de.danoeh.antennapod.core.service.playback.PlaybackService;
import de.danoeh.antennapod.core.storage.DBReader;
import de.danoeh.antennapod.core.storage.DBTasks;
import de.danoeh.antennapod.core.storage.DBWriter;
import de.danoeh.antennapod.core.util.FeedItemUtil;
import de.danoeh.antennapod.core.util.Flavors;
import de.danoeh.antennapod.core.util.StorageUtils;
import de.danoeh.antennapod.dialog.RatingDialog;
import de.danoeh.antennapod.dialog.RenameFeedDialog;
import de.danoeh.antennapod.fragment.AddFeedFragment;
import de.danoeh.antennapod.fragment.DownloadsFragment;
import de.danoeh.antennapod.fragment.EpisodesFragment;
import de.danoeh.antennapod.fragment.ExternalPlayerFragment;
import de.danoeh.antennapod.fragment.ItemlistFragment;
import de.danoeh.antennapod.fragment.PlaybackHistoryFragment;
import de.danoeh.antennapod.fragment.QueueFragment;
import de.danoeh.antennapod.fragment.SubscriptionFragment;
import de.danoeh.antennapod.menuhandler.NavDrawerActivity;
import de.greenrobot.event.EventBus;
import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
//DEEPAK
import de.danoeh.antennapod.fragment.gpodnet.GpodnetMainFragment;
/**
 * The activity that is shown when the user launches the app.
 */
public class MainActivity extends CastEnabledActivity implements NavDrawerActivity {

    private static final String TAG = "MainActivity";

    private static final int EVENTS = EventDistributor.FEED_LIST_UPDATE
            | EventDistributor.UNREAD_ITEMS_UPDATE;

    public static final String PREF_NAME = "MainActivityPrefs";
    public static final String PREF_IS_FIRST_LAUNCH = "prefMainActivityIsFirstLaunch";
    public static final String PREF_LAST_FRAGMENT_TAG = "prefMainActivityLastFragmentTag";

    public static final String EXTRA_NAV_TYPE = "nav_type";
    public static final String EXTRA_NAV_INDEX = "nav_index";
    public static final String EXTRA_FRAGMENT_TAG = "fragment_tag";
    public static final String EXTRA_FRAGMENT_ARGS = "fragment_args";
    public static final String EXTRA_FEED_ID = "fragment_feed_id";

    public static final String SAVE_BACKSTACK_COUNT = "backstackCount";
    public static final String SAVE_TITLE = "title";

    public static final String[] NAV_DRAWER_TAGS = {
            AddFeedFragment.TAG,
            QueueFragment.TAG,
            EpisodesFragment.TAG,
            SubscriptionFragment.TAG,
            DownloadsFragment.TAG,
            PlaybackHistoryFragment.TAG,
            //AddFeedFragment.TAG,
            "Sign Out",
            NavListAdapter.SUBSCRIPTION_LIST_TAG

    };

    private Toolbar toolbar;
    private ExternalPlayerFragment externalPlayerFragment;
    private DrawerLayout drawerLayout;

    private View navDrawer;
    private ListView navList;
    private NavListAdapter navAdapter;
    private int mPosition = -1;

    private ActionBarDrawerToggle drawerToggle;

    private CharSequence currentTitle;

    private ProgressDialog pd;

    private Subscription subscription;

    //Analytics
    private FirebaseAnalytics mFirebaseAnalytics;
    private EventDistributor.EventListener listener = new EventDistributor.EventListener() {
        @Override
        public void update(EventDistributor eventDistributor, Integer arg) {
            if ((arg & EventDistributor.CURRENT_MEDIA_UPDATE) != 0) {
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getApplicationContext());
                long feedId = prefs.getLong(PlaybackPreferences.PREF_CURRENTLY_PLAYING_FEED_ID,
                        PlaybackPreferences.NO_MEDIA_PLAYING);
                long mediaId = prefs.getLong(
                        PlaybackPreferences.PREF_CURRENTLY_PLAYING_FEEDMEDIA_ID,
                        PlaybackPreferences.NO_MEDIA_PLAYING);
                boolean isStream = prefs.getBoolean(
                        PlaybackPreferences.PREF_CURRENT_EPISODE_IS_STREAM,
                        false);
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                Feed feed = DBReader.getFeed(feedId);
                FeedMedia feedMedia = DBReader.getFeedMedia(mediaId);

                //User Name, ID, Feed Title, Episode Title
                String Username = user.getDisplayName();
                String userId = user.getUid();
                String feedTitle = feed.getTitle();
                String episode = feedMedia.getEpisodeTitle();

                //send this information to Firebase Analytics
                sendToFirebase(Username, userId, feedTitle, episode);

              /*  updater = Observable.fromCallable(DBReader::getFeedList)
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                feeds -> {
                                    OnlineFeedViewActivity.this.feeds = feeds;
                                    // setSubscribeButtonState(feed);
                                    //ChangetoMain();
                                }, error -> {
                                    Log.e(TAG, Log.getStackTraceString(error));
                                }
                        ); */
            }
        }
    };

    private void readCurrentPlayedMedia(){
    }

    private void sendToFirebase (String uname, String uid, String feed, String episode){
        String Date= DateFormat.getDateTimeInstance().format(new Date());
        Bundle params = new Bundle();
        params.putString("User_name", uname);
        params.putString("User_id", uid);
        params.putString("Feed_id", feed);
        params.putString("episode_id", episode);
        params.putString("time", Date);
        mFirebaseAnalytics.logEvent("played_episode", params);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setTheme(UserPreferences.getNoTitleTheme());
        super.onCreate(savedInstanceState);
        // Obtain the FirebaseAnalytics instance.
        final TempFeedRemover Tempremover = new TempFeedRemover(this);
        Tempremover.executeAsync();
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
        StorageUtils.checkStorageAvailability(this);
        setContentView(R.layout.main);

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            findViewById(R.id.shadow).setVisibility(View.GONE);
            int elevation = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4,
                    getResources().getDisplayMetrics());
            getSupportActionBar().setElevation(elevation);
        }

        currentTitle = getTitle();

        drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        navList = (ListView) findViewById(R.id.nav_list);
        navDrawer = findViewById(R.id.nav_layout);

        drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.drawer_open, R.string.drawer_close);
        if (savedInstanceState != null) {
            int backstackCount = savedInstanceState.getInt(SAVE_BACKSTACK_COUNT, 0);
            drawerToggle.setDrawerIndicatorEnabled(backstackCount == 0);
        }
        drawerLayout.setDrawerListener(drawerToggle);

        final FragmentManager fm = getSupportFragmentManager();

        fm.addOnBackStackChangedListener(() -> drawerToggle.setDrawerIndicatorEnabled(fm.getBackStackEntryCount() == 0));

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        navAdapter = new NavListAdapter(itemAccess, this);
        navList.setAdapter(navAdapter);
        navList.setOnItemClickListener(navListClickListener);
        navList.setOnItemLongClickListener(newListLongClickListener);
        registerForContextMenu(navList);

        navAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                selectedNavListIndex = getSelectedNavListIndex();
            }
        });

        findViewById(R.id.nav_settings).setOnClickListener(v -> {
            drawerLayout.closeDrawer(navDrawer);
            startActivity(new Intent(MainActivity.this, PreferenceActivity.class));
        });

        FragmentTransaction transaction = fm.beginTransaction();

        //DEEPAK Fragment mainFragment = fm.findFragmentByTag("main");
        //Fragment mainFragment = new SubscriptionFragment();
        Fragment mainFragment = new GpodnetMainFragment();
        if (mainFragment != null) {
            Log.d(TAG, "main Fragment() -> " + mainFragment);
            transaction.replace(R.id.main_view, mainFragment);
        } else {
            String lastFragment = getLastNavFragment();
            if (ArrayUtils.contains(NAV_DRAWER_TAGS, lastFragment)) {
                loadFragment(lastFragment, null);
            } else {
                try {
                    loadFeedFragmentById(Integer.parseInt(lastFragment), null);
                } catch (NumberFormatException e) {
                    // it's not a number, this happens if we removed
                    // a label from the NAV_DRAWER_TAGS
                    // give them a nice default...
                    loadFragment(QueueFragment.TAG, null);
                }
            }
        }
        externalPlayerFragment = new ExternalPlayerFragment();
        transaction.replace(R.id.playerFragment, externalPlayerFragment, ExternalPlayerFragment.TAG);
        transaction.commit();

        checkFirstLaunch();
    }

    private void saveLastNavFragment(String tag) {
        Log.d(TAG, "saveLastNavFragment(tag: " + tag +")");
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        if(tag != null) {
            edit.putString(PREF_LAST_FRAGMENT_TAG, tag);
        } else {
            edit.remove(PREF_LAST_FRAGMENT_TAG);
        }
        edit.apply();
    }

    private String getLastNavFragment() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String lastFragment = prefs.getString(PREF_LAST_FRAGMENT_TAG, QueueFragment.TAG);
        Log.d(TAG, "getLastNavFragment() -> " + lastFragment);
        return lastFragment;
    }

    private void checkFirstLaunch() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_IS_FIRST_LAUNCH, true)) {
            new Handler().postDelayed(() -> drawerLayout.openDrawer(navDrawer), 1500);

            // for backward compatibility, we only change defaults for fresh installs
            UserPreferences.setUpdateInterval(12);

            SharedPreferences.Editor edit = prefs.edit();
            edit.putBoolean(PREF_IS_FIRST_LAUNCH, false);
            edit.commit();
        }
    }

    public void showDrawerPreferencesDialog() {
        final List<String> hiddenDrawerItems = UserPreferences.getHiddenDrawerItems();
        String[] navLabels = new String[NAV_DRAWER_TAGS.length];
        final boolean[] checked = new boolean[NAV_DRAWER_TAGS.length];
        for (int i = 0; i < NAV_DRAWER_TAGS.length; i++) {
            String tag = NAV_DRAWER_TAGS[i];
            navLabels[i] = navAdapter.getLabel(tag);
            if (!hiddenDrawerItems.contains(tag)) {
                checked[i] = true;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(R.string.drawer_preferences);
        builder.setMultiChoiceItems(navLabels, checked, (dialog, which, isChecked) -> {
            if (isChecked) {
                hiddenDrawerItems.remove(NAV_DRAWER_TAGS[which]);
            } else {
                hiddenDrawerItems.add(NAV_DRAWER_TAGS[which]);
            }
        });
        builder.setPositiveButton(R.string.confirm_label, (dialog, which) -> UserPreferences.setHiddenDrawerItems(hiddenDrawerItems));
        builder.setNegativeButton(R.string.cancel_label, null);
        builder.create().show();
    }

    public boolean isDrawerOpen() {
        return drawerLayout != null && navDrawer != null && drawerLayout.isDrawerOpen(navDrawer);
    }

    public List<Feed> getFeeds() {
        return (navDrawerData != null) ? navDrawerData.feeds : null;
    }

    public void loadFragment(int index, Bundle args) {
        Log.d(TAG, "loadFragment(index: " + index + ", args: " + args + ")");
        if (index < navAdapter.getSubscriptionOffset()) {
            String tag = navAdapter.getTags().get(index);
            loadFragment(tag, args);
        } else {
            int pos = index - navAdapter.getSubscriptionOffset();
            loadFeedFragmentByPosition(pos, args);
        }
    }

    public void loadFragment(String tag, Bundle args) {
        Log.d(TAG, "loadFragment(tag: " + tag + ", args: " + args + ")");
        Fragment fragment = null;
        switch (tag) {
            case QueueFragment.TAG:
                fragment = new QueueFragment();
                break;
            case EpisodesFragment.TAG:
                fragment = new EpisodesFragment();
                break;
            case DownloadsFragment.TAG:
                fragment = new DownloadsFragment();
                break;
            case PlaybackHistoryFragment.TAG:
                fragment = new PlaybackHistoryFragment();
                break;
            case AddFeedFragment.TAG:
                fragment = new GpodnetMainFragment();
                break;
            case SubscriptionFragment.TAG:
                SubscriptionFragment subscriptionFragment = new SubscriptionFragment();
                fragment = subscriptionFragment;
                break;
            default:
                // default to the queue
                tag = QueueFragment.TAG;
                fragment = new QueueFragment();
                args = null;
                break;
        }
        currentTitle = navAdapter.getLabel(tag);
        getSupportActionBar().setTitle(currentTitle);
        saveLastNavFragment(tag);
        if (args != null) {
            fragment.setArguments(args);
        }
        loadFragment(fragment);
    }

    private void loadFeedFragmentByPosition(int relPos, Bundle args) {
        if(relPos < 0) {
            return;
        }
        Feed feed = itemAccess.getItem(relPos);
        loadFeedFragmentById(feed.getId(), args);
    }

    public void loadFeedFragmentById(long feedId, Bundle args) {
        Fragment fragment = ItemlistFragment.newInstance(feedId);
        if(args != null) {
            fragment.setArguments(args);
        }
        saveLastNavFragment(String.valueOf(feedId));
        currentTitle = "";
        getSupportActionBar().setTitle(currentTitle);
        loadFragment(fragment);
    }

    private void loadFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        // clear back stack
        for (int i = 0; i < fragmentManager.getBackStackEntryCount(); i++) {
            fragmentManager.popBackStack();
        }
        FragmentTransaction t = fragmentManager.beginTransaction();
        t.replace(R.id.main_view, fragment, "main");
        fragmentManager.popBackStack();
        // TODO: we have to allow state loss here
        // since this function can get called from an AsyncTask which
        // could be finishing after our app has already committed state
        // and is about to get shutdown.  What we *should* do is
        // not commit anything in an AsyncTask, but that's a bigger
        // change than we want now.
        t.commitAllowingStateLoss();
        if (navAdapter != null) {
            navAdapter.notifyDataSetChanged();
        }
    }

    public void loadChildFragment(Fragment fragment) {
        Validate.notNull(fragment);
        FragmentManager fm = getSupportFragmentManager();
        fm.beginTransaction()
                .replace(R.id.main_view, fragment, "main")
                .addToBackStack(null)
                .commit();
    }

    public void dismissChildFragment() {
        getSupportFragmentManager().popBackStack();
    }

    private int getSelectedNavListIndex() {
        String currentFragment = getLastNavFragment();
        if(currentFragment == null) {
            // should not happen, but better safe than sorry
            return -1;
        }
        int tagIndex = navAdapter.getTags().indexOf(currentFragment);
        if(tagIndex >= 0) {
            return tagIndex;
        } else if(ArrayUtils.contains(NAV_DRAWER_TAGS, currentFragment)) {
            // the fragment was just hidden
            return -1;
        } else { // last fragment was not a list, but a feed
            long feedId = Long.parseLong(currentFragment);
            if (navDrawerData != null) {
                List<Feed> feeds = navDrawerData.feeds;
                for (int i = 0; i < feeds.size(); i++) {
                    if (feeds.get(i).getId() == feedId) {
                        return i + navAdapter.getSubscriptionOffset();
                    }
                }
            }
            return -1;
        }
    }

    private AdapterView.OnItemClickListener navListClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            int viewType = parent.getAdapter().getItemViewType(position);
            /*String tag1 = navAdapter.gettag1(position);
            Log.d(TAG, "SIGN OUT CLICKED() -> " + tag1);
           if (position==(navAdapter.getSubscriptionOffset()-1)){  //TODO - fix this with better code
                Log.d(TAG, "SIGN OUT CLICKED() -> ");
                signout();
            } else{ */
                if (viewType != NavListAdapter.VIEW_TYPE_SECTION_DIVIDER && position != selectedNavListIndex) {
                loadFragment(position, null);
            }
            //}
            drawerLayout.closeDrawer(navDrawer);
        }
    };

    private void signout(){
        Intent intent = new Intent(this, mygooglesignin.class);
        intent.putExtra("message", "signout");
        startActivity(intent);
        finish();
    }

    private AdapterView.OnItemLongClickListener newListLongClickListener = new AdapterView.OnItemLongClickListener() {
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            if(position < navAdapter.getTags().size()) {
                showDrawerPreferencesDialog();
                return true;
            } else {
                mPosition = position;
                return false;
            }
        }
    };


    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
        if (savedInstanceState != null) {
            currentTitle = savedInstanceState.getString(SAVE_TITLE);
            if (!drawerLayout.isDrawerOpen(navDrawer)) {
                getSupportActionBar().setTitle(currentTitle);
            }
            selectedNavListIndex = getSelectedNavListIndex();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SAVE_TITLE, getSupportActionBar().getTitle().toString());
        outState.putInt(SAVE_BACKSTACK_COUNT, getSupportFragmentManager().getBackStackEntryCount());
    }

    @Override
    public void onStart() {
        super.onStart();
        EventDistributor.getInstance().register(contentUpdate);
        EventDistributor.getInstance().register(listener);
        EventBus.getDefault().register(this);
        RatingDialog.init(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StorageUtils.checkStorageAvailability(this);
        DBTasks.checkShouldRefreshFeeds(getApplicationContext());

        Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_FEED_ID) ||
                (navDrawerData != null && intent.hasExtra(EXTRA_NAV_TYPE) &&
                        (intent.hasExtra(EXTRA_NAV_INDEX) || intent.hasExtra(EXTRA_FRAGMENT_TAG)))) {
            handleNavIntent();
        }
        loadData();
        RatingDialog.check();
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventDistributor.getInstance().unregister(contentUpdate);
        EventDistributor.getInstance().unregister(listener);
        EventBus.getDefault().unregister(this);
        if(subscription != null) {
            subscription.unsubscribe();
        }
        if(pd != null) {
            pd.dismiss();
        }
    }


    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Glide.get(this).trimMemory(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Glide.get(this).clearMemory();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean retVal = super.onCreateOptionsMenu(menu);
        if (Flavors.FLAVOR == Flavors.PLAY) {
            switch (getLastNavFragment()) {
                case QueueFragment.TAG:
                case EpisodesFragment.TAG:
                    requestCastButton(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                    return retVal;
                case DownloadsFragment.TAG:
                case PlaybackHistoryFragment.TAG:
                case AddFeedFragment.TAG:
                case SubscriptionFragment.TAG:
                    return retVal;
                default:
                    requestCastButton(MenuItem.SHOW_AS_ACTION_NEVER);
                    return retVal;
            }
        } else {
            return retVal;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        } else if (item.getItemId() == android.R.id.home) {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                dismissChildFragment();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }


    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if(v.getId() != R.id.nav_list) {
            return;
        }
        AdapterView.AdapterContextMenuInfo adapterInfo = (AdapterView.AdapterContextMenuInfo) menuInfo;
        int position = adapterInfo.position;
        if(position < navAdapter.getSubscriptionOffset()) {
            return;
        }
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.nav_feed_context, menu);
        Feed feed = navDrawerData.feeds.get(position - navAdapter.getSubscriptionOffset());
        menu.setHeaderTitle(feed.getTitle());
        // episodes are not loaded, so we cannot check if the podcast has new or unplayed ones!
    }


    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final int position = mPosition;
        mPosition = -1; // reset
        if(position < 0) {
            return false;
        }
        Feed feed = navDrawerData.feeds.get(position - navAdapter.getSubscriptionOffset());
        switch(item.getItemId()) {
            case R.id.mark_all_seen_item:
                DBWriter.markFeedSeen(feed.getId());
                return true;
            case R.id.mark_all_read_item:
                DBWriter.markFeedRead(feed.getId());
                return true;
            case R.id.rename_item:
                new RenameFeedDialog(this, feed).show();
                return true;
            case R.id.remove_item:
                final FeedRemover remover = new FeedRemover(this, feed) {
                    @Override
                    protected void onPostExecute(Void result) {
                        super.onPostExecute(result);
                        if(getSelectedNavListIndex() == position) {
                            loadFragment(EpisodesFragment.TAG, null);
                        }
                    }
                };
                ConfirmationDialog conDialog = new ConfirmationDialog(this,
                        R.string.remove_feed_label,
                        getString(R.string.feed_delete_confirmation_msg, feed.getTitle())) {
                    @Override
                    public void onConfirmButtonPressed(
                            DialogInterface dialog) {
                        dialog.dismiss();
                        long mediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
                        if (mediaId > 0 &&
                                FeedItemUtil.indexOfItemWithMediaId(feed.getItems(), mediaId) >= 0) {
                            Log.d(TAG, "Currently playing episode is about to be deleted, skipping");
                            remover.skipOnCompletion = true;
                            int playerStatus = PlaybackPreferences.getCurrentPlayerStatus();
                            if(playerStatus == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
                                sendBroadcast(new Intent(
                                        PlaybackService.ACTION_PAUSE_PLAY_CURRENT_EPISODE));
                            }
                        }
                        remover.executeAsync();
                    }
                };
                conDialog.createNewDialog().show();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        if(isDrawerOpen()) {
            drawerLayout.closeDrawer(navDrawer);
        } else {
            onBackPressed1();
        }
    }

    private DBReader.NavDrawerData navDrawerData;
    private int selectedNavListIndex = 0;

    private NavListAdapter.ItemAccess itemAccess = new NavListAdapter.ItemAccess() {
        @Override
        public int getCount() {
            if (navDrawerData != null) {
                return navDrawerData.feeds.size();
            } else {
                return 0;
            }
        }

        @Override
        public Feed getItem(int position) {
            if (navDrawerData != null && 0 <= position && position < navDrawerData.feeds.size()) {
                return navDrawerData.feeds.get(position);
            } else {
                return null;
            }
        }

        @Override
        public int getSelectedItemIndex() {
            return selectedNavListIndex;
        }

        @Override
        public int getQueueSize() {
            return (navDrawerData != null) ? navDrawerData.queueSize : 0;
        }

        @Override
        public int getNumberOfNewItems() {
            return (navDrawerData != null) ? navDrawerData.numNewItems : 0;
        }

        @Override
        public int getNumberOfDownloadedItems() {
            return (navDrawerData != null) ? navDrawerData.numDownloadedItems : 0;
        }

        @Override
        public int getReclaimableItems() {
            return (navDrawerData != null) ? navDrawerData.reclaimableSpace : 0;
        }

        @Override
        public int getFeedCounter(long feedId) {
            return navDrawerData != null ? navDrawerData.feedCounters.get(feedId) : 0;
        }

        @Override
        public int getFeedCounterSum() {
            if(navDrawerData == null) {
                return 0;
            }
            int sum = 0;
            for(int counter : navDrawerData.feedCounters.values()) {
                sum += counter;
            }
            return sum;
        }

    };

    private void loadData() {
        subscription = Observable.fromCallable(DBReader::getNavDrawerData)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    boolean handleIntent = (navDrawerData == null);

                    navDrawerData = result;
                    navAdapter.notifyDataSetChanged();

                    if (handleIntent) {
                        handleNavIntent();
                    }
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    public void onEvent(QueueEvent event) {
        Log.d(TAG, "onEvent(" + event + ")");
        // we are only interested in the number of queue items, not download status or position
        if(event.action == QueueEvent.Action.DELETED_MEDIA ||
                event.action == QueueEvent.Action.SORTED ||
                event.action == QueueEvent.Action.MOVED) {
            return;
        }
        loadData();
    }

    public void onEventMainThread(ProgressEvent event) {
        Log.d(TAG, "onEvent(" + event + ")");
        switch(event.action) {
            case START:
                pd = new ProgressDialog(this);
                pd.setMessage(event.message);
                pd.setIndeterminate(true);
                pd.setCancelable(false);
                pd.show();
                break;
            case END:
                if(pd != null) {
                    pd.dismiss();
                }
                break;
        }
    }

    public void onEventMainThread(MessageEvent event) {
        Log.d(TAG, "onEvent(" + event + ")");
        View parentLayout = findViewById(R.id.drawer_layout);
        Snackbar snackbar = Snackbar.make(parentLayout, event.message, Snackbar.LENGTH_SHORT);
        if(event.action != null) {
            snackbar.setAction(getString(R.string.undo), v -> {
                event.action.run();
            });
        }
        snackbar.show();
    }

    private EventDistributor.EventListener contentUpdate = new EventDistributor.EventListener() {

        @Override
        public void update(EventDistributor eventDistributor, Integer arg) {
            if ((EVENTS & arg) != 0) {
                Log.d(TAG, "Received contentUpdate Intent.");
                loadData();
            }
        }
    };

    private void handleNavIntent() {
        Log.d(TAG, "handleNavIntent()");
        Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_FEED_ID) ||
                (intent.hasExtra(EXTRA_NAV_TYPE) &&
                        (intent.hasExtra(EXTRA_NAV_INDEX) || intent.hasExtra(EXTRA_FRAGMENT_TAG)))) {
            int index = intent.getIntExtra(EXTRA_NAV_INDEX, -1);
            String tag = intent.getStringExtra(EXTRA_FRAGMENT_TAG);
            Bundle args = intent.getBundleExtra(EXTRA_FRAGMENT_ARGS);
            long feedId = intent.getLongExtra(EXTRA_FEED_ID, 0);
            if (index >= 0) {
                loadFragment(index, args);
            } else if (tag != null) {
                loadFragment(tag, args);
            } else if(feedId > 0) {
                loadFeedFragmentById(feedId, args);
            }
        }
        setIntent(new Intent(MainActivity.this, MainActivity.class)); // to avoid handling the intent twice when the configuration changes
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    boolean doubleBackToExitPressedOnce = false;


    public void onBackPressed1() {
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed();
            return;
        }

        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show();

        //getFragmentManager().popBackStack();
        final FragmentManager fm = getSupportFragmentManager();
        fm.popBackStack();

        new Handler().postDelayed(new Runnable() {

            @Override
            public void run() {
                doubleBackToExitPressedOnce = false;
            }
        }, 2000);
    }
}