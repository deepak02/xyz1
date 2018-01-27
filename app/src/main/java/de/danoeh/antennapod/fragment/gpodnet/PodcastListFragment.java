package de.danoeh.antennapod.fragment.gpodnet;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.activity.OnlineFeedViewActivity;
import de.danoeh.antennapod.adapter.gpodnet.PodcastListAdapter;
import de.danoeh.antennapod.core.dialog.DownloadRequestErrorDialogCreator;
import de.danoeh.antennapod.core.feed.EventDistributor;
import de.danoeh.antennapod.core.feed.Feed;
import de.danoeh.antennapod.core.feed.FeedItem;
import de.danoeh.antennapod.core.feed.FeedPreferences;
import de.danoeh.antennapod.core.gpoddernet.GpodnetService;
import de.danoeh.antennapod.core.gpoddernet.GpodnetServiceException;
import de.danoeh.antennapod.core.gpoddernet.model.GpodnetPodcast;
import de.danoeh.antennapod.core.service.download.DownloadRequest;
import de.danoeh.antennapod.core.service.download.DownloadStatus;
import de.danoeh.antennapod.core.service.download.Downloader;
import de.danoeh.antennapod.core.service.download.HttpDownloader;
import de.danoeh.antennapod.core.storage.DBReader;
import de.danoeh.antennapod.core.storage.DownloadRequestException;
import de.danoeh.antennapod.core.storage.DownloadRequester;
import de.danoeh.antennapod.core.syndication.handler.FeedHandler;
import de.danoeh.antennapod.core.syndication.handler.FeedHandlerResult;
import de.danoeh.antennapod.core.syndication.handler.UnsupportedFeedtypeException;
import de.danoeh.antennapod.core.util.DownloadError;
import de.danoeh.antennapod.core.util.FileNameGenerator;
import de.danoeh.antennapod.core.util.URLChecker;
import de.danoeh.antennapod.core.util.syndication.HtmlToPlainText;
import de.danoeh.antennapod.fragment.ItemlistFragment;
import de.danoeh.antennapod.menuhandler.MenuItemUtils;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

/**
 * Displays a list of GPodnetPodcast-Objects in a GridView
 */
public abstract class PodcastListFragment extends Fragment {

    private static final String TAG = "PodcastListFragment";

    private GridView gridView;
    private ProgressBar progressBar;
    private TextView txtvError;
    private Button butRetry;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.gpodder_podcasts, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        final SearchView sv = (SearchView) MenuItemCompat.getActionView(searchItem);
        MenuItemUtils.adjustTextColor(getActivity(), sv);
        sv.setQueryHint(getString(R.string.gpodnet_search_hint));
        sv.setOnQueryTextListener(new android.support.v7.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String s) {
                sv.clearFocus();
                MainActivity activity = (MainActivity)getActivity();
                if (activity != null) {
                    activity.loadChildFragment(SearchListFragment.newInstance(s));
                }
                return true;
            }

            @Override
            public boolean onQueryTextChange(String s) {
                return false;
            }
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.gpodnet_podcast_list, container, false);

        gridView = (GridView) root.findViewById(R.id.gridView);
        progressBar = (ProgressBar) root.findViewById(R.id.progressBar);
        txtvError = (TextView) root.findViewById(R.id.txtvError);
        butRetry = (Button) root.findViewById(R.id.butRetry);

        gridView.setOnItemClickListener((parent, view, position, id) ->
                onPodcastSelected((GpodnetPodcast) gridView.getAdapter().getItem(position)));
        butRetry.setOnClickListener(v -> loadData());

        loadData();
        return root;
    }

    protected void onPodcastSelected(GpodnetPodcast selection) {
        Log.d(TAG, "Selected podcast: " + selection.toString());

        startFeedDownload(selection.getUrl(), null, null);
        if (feed_exists ==0 ) {
        /*Intent intent = new Intent(getActivity(), OnlineFeedViewActivity.class);
        intent.putExtra(OnlineFeedViewActivity.ARG_FEEDURL, selection.getUrl());
        intent.putExtra(OnlineFeedViewActivity.ARG_TITLE, getString(R.string.gpodnet_main_label));
        startActivity(intent);*/
        }
    }

    protected abstract List<GpodnetPodcast> loadPodcastData(GpodnetService service) throws GpodnetServiceException;

    protected final void loadData() {
        AsyncTask<Void, Void, List<GpodnetPodcast>> loaderTask = new AsyncTask<Void, Void, List<GpodnetPodcast>>() {
            volatile Exception exception = null;

            @Override
            protected List<GpodnetPodcast> doInBackground(Void... params) {
                GpodnetService service = null;
                try {
                    service = new GpodnetService();
                    return loadPodcastData(service);
                } catch (GpodnetServiceException e) {
                    exception = e;
                    e.printStackTrace();
                    return null;
                } finally {
                    if (service != null) {
                        service.shutdown();
                    }
                }
            }

            @Override
            protected void onPostExecute(List<GpodnetPodcast> gpodnetPodcasts) {
                super.onPostExecute(gpodnetPodcasts);
                final Context context = getActivity();
                if (context != null && gpodnetPodcasts != null && gpodnetPodcasts.size() > 0) {
                    PodcastListAdapter listAdapter = new PodcastListAdapter(context, 0, gpodnetPodcasts);
                    gridView.setAdapter(listAdapter);
                    listAdapter.notifyDataSetChanged();

                    progressBar.setVisibility(View.GONE);
                    gridView.setVisibility(View.VISIBLE);
                    txtvError.setVisibility(View.GONE);
                    butRetry.setVisibility(View.GONE);
                } else if (context != null && gpodnetPodcasts != null) {
                    gridView.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    txtvError.setText(getString(R.string.search_status_no_results));
                    txtvError.setVisibility(View.VISIBLE);
                    butRetry.setVisibility(View.GONE);
                } else if (context != null) {
                    gridView.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    txtvError.setText(getString(R.string.error_msg_prefix) + exception.getMessage());
                    txtvError.setVisibility(View.VISIBLE);
                    butRetry.setVisibility(View.VISIBLE);
                }
            }

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                gridView.setVisibility(View.GONE);
                progressBar.setVisibility(View.VISIBLE);
                txtvError.setVisibility(View.GONE);
                butRetry.setVisibility(View.GONE);
            }
        };

        loaderTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }


    //DEEPAK - TRY
    private Feed feed;
    private Subscription download;
    public volatile List<Feed> feeds;
    private String selectedDownloadUrl;
    private Downloader downloader;
    private Subscription parser;
    private WeakReference<MainActivity> mainActivityRef;
    public int feed_exists = 0;
    private Subscription updater;
    private static final int EVENTS = EventDistributor.FEED_LIST_UPDATE;

    private void startFeedDownload(String url, String username, String password) {
        Log.d(TAG, "Starting feed download");
        url = URLChecker.prepareURL(url);
        feed = new Feed(url, null);
        if (username != null && password != null) {
            feed.setPreferences(new FeedPreferences(0, false, FeedPreferences.AutoDeleteAction.GLOBAL, username, password));
        }
        String fileUrl = new File(getActivity().getExternalCacheDir(),
                FileNameGenerator.generateFileName(feed.getDownload_url())).toString();
        feed.setFile_url(fileUrl);
        final DownloadRequest request = new DownloadRequest(feed.getFile_url(),
                feed.getDownload_url(), "OnlineFeed", 0, Feed.FEEDFILETYPE_FEED, username, password,
                true, null);

        download = Observable.create(new Observable.OnSubscribe<DownloadStatus>() {
            @Override
            public void call(Subscriber<? super DownloadStatus> subscriber) {
                feeds = DBReader.getFeedList();
                downloader = new HttpDownloader(request);
                downloader.call();
                Log.d(TAG, "Download was completed");
                subscriber.onNext(downloader.getResult());
                subscriber.onCompleted();
            }
        })
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::checkDownloadResult,
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void checkDownloadResult(DownloadStatus status) {
        if (status == null) {
            Log.wtf(TAG, "DownloadStatus returned by Downloader was null");
          //  finish();  //DEEPAKTODO
        }
        if (status.isCancelled()) {
            return;
        }
        if (status.isSuccessful()) {
            parseFeed();
        } /*else if (status.getReason() == DownloadError.ERROR_UNAUTHORIZED) {  //DEEPAKTODO
            if (!isFinishing() && !isPaused) {
                dialog = new OnlineFeedViewActivity.FeedViewAuthenticationDialog(OnlineFeedViewActivity.this,
                        R.string.authentication_notification_title, downloader.getDownloadRequest().getSource());
                dialog.show();
            }
        } */else {
            String errorMsg = status.getReason().getErrorString(getActivity());
            if (errorMsg != null && status.getReasonDetailed() != null) {
                errorMsg += " (" + status.getReasonDetailed() + ")";
            }
         //   showErrorDialog(errorMsg);  //DEEPAKTODO
        }
    }
    private void parseFeed() {
        if (feed == null || feed.getFile_url() == null && feed.isDownloaded()) {
            throw new IllegalStateException("feed must be non-null and downloaded when parseFeed is called");
        }
        Log.d(TAG, "Parsing feed");

        parser = Observable.create(new Observable.OnSubscribe<FeedHandlerResult>() {
            @Override
            public void call(Subscriber<? super FeedHandlerResult> subscriber) {
                FeedHandler handler = new FeedHandler();
                try {
                    FeedHandlerResult result = handler.parseFeed(feed);
                    subscriber.onNext(result);
                } catch (UnsupportedFeedtypeException e) {
                    Log.d(TAG, "Unsupported feed type detected");
                    /*if (TextUtils.equals("html", e.getRootElement().toLowerCase())) {  //DEEPAKTODO
                        showFeedDiscoveryDialog(new File(feed.getFile_url()), feed.getDownload_url());
                    } else { */
                        subscriber.onError(e);
                    //}
                } catch (Exception e) {
                    Log.e(TAG, Log.getStackTraceString(e));
                    subscriber.onError(e);
                } finally {
                    boolean rc = new File(feed.getFile_url()).delete();
                    Log.d(TAG, "Deleted feed source file. Result: " + rc);
                    subscriber.onCompleted();
                }
            }
        })
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    beforeShowFeedInformation(result.feed);
                    ToFeedFragment(result.feed);
             //       showFeedInformation(result.feed, result.alternateFeedUrls);
                }, error -> {
                    String errorMsg = DownloadError.ERROR_PARSER_EXCEPTION.getErrorString(
                            getActivity()) + " (" + error.getMessage() + ")";
                    // DEEPAKTODO showErrorDialog(errorMsg);
                });
    }
    private void beforeShowFeedInformation(Feed feed) {
        final HtmlToPlainText formatter = new HtmlToPlainText();
        if(Feed.TYPE_ATOM1.equals(feed.getType()) && feed.getDescription() != null) {
            // remove HTML tags from descriptions
            Log.d(TAG, "Removing HTML from feed description");
            Document feedDescription = Jsoup.parse(feed.getDescription());
            feed.setDescription(StringUtils.trim(formatter.getPlainText(feedDescription)));
        }
        Log.d(TAG, "Removing HTML from shownotes");
        if (feed.getItems() != null) {
            for (FeedItem item : feed.getItems()) {
                if (item.getDescription() != null) {
                    Document itemDescription = Jsoup.parse(item.getDescription());
                    item.setDescription(StringUtils.trim(formatter.getPlainText(itemDescription)));
                }
            }
        }
    }
    private boolean feedInFeedlist(Feed feed) {
        if (feeds == null || feed == null) {
            return false;
        }
        for (Feed f : feeds) {
            if (f.getIdentifyingValue().equals(feed.getIdentifyingValue())) {
                return true;
            }
        }
        return false;
    }

    private long getFeedId(Feed feed) {
        if (feeds == null || feed == null) {
            return 0;
        }
        for (Feed f : feeds) {
            if (f.getIdentifyingValue().equals(feed.getIdentifyingValue())) {
                return f.getId();
            }
        }
        return 0;
    }

    private EventDistributor.EventListener listener = new EventDistributor.EventListener() {
        @Override
        public void update(EventDistributor eventDistributor, Integer arg) {
            if ((arg & EventDistributor.FEED_LIST_UPDATE) != 0) {
                updater = Observable.fromCallable(DBReader::getFeedList)
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                feeds -> {
                                    setfeeds(feeds);
                                    showfeedFragment();
                                    //this.feeds = feeds;
                                    //setSubscribeButtonState(feed);
                                    //ChangetoMain();
                                }, error -> {
                                    Log.e(TAG, Log.getStackTraceString(error));
                                }
                        );
            } else if ((arg & EVENTS) != 0) {
               // setSubscribeButtonState(feed);
            }
        }
    };
    private void setfeeds(List<Feed> feeds1){
        feeds = feeds1;
    }

    private void showfeedFragment(){
        mainActivityRef = new WeakReference<>((MainActivity)getActivity());
        Fragment fragment = ItemlistFragment.newInstance(getFeedId(feed));
        mainActivityRef.get().loadChildFragment(fragment);
    }
    private void ToFeedFragment(Feed feed1){
        if(feed1 != null && feedInFeedlist(feed1)) {
            feed_exists=1;
            this.feed = feed1;
            showfeedFragment();
        } else {
            feed_exists =0 ;
            this.selectedDownloadUrl = feed1.getDownload_url();
            EventDistributor.getInstance().register(listener);
            Feed f = new Feed(selectedDownloadUrl, null, feed.getTitle());
            f.setPreferences(feed.getPreferences());
            this.feed = f;
            try {
                DownloadRequester.getInstance().downloadFeed(getActivity(), f);
            } catch (DownloadRequestException e) {
                Log.e(TAG, Log.getStackTraceString(e));
                DownloadRequestErrorDialogCreator.newRequestErrorDialog(getActivity(), e.getMessage());
            }
        }
    }

}
