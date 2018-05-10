package com.wojtek.biblioteczka;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import jcifs.smb.SmbFile;

public class MainActivity extends AppCompatActivity {

    static final int ADD_BOOK_REQUEST = 1;
    static final int EDIT_BOOK_REQUEST = 2;
    static final int SHOW_BOOK_REQUEST = 3;

    static final String tag = "MainActivity";

    private File dataFile;
    private Bookcase bookcase;
    private Comparator<Book> comparator;

    private BookArrayAdapter adapter;
    private ArrayList<Book> adapterData;

    private Context context;
    private SharedPreferences sharedPref;

    private class BookArrayAdapter extends ArrayAdapter {
        private ArrayList<Book> booksOriginal;
        private ArrayList<Book> booksFiltered;
        private BookFilter filter;

        public BookArrayAdapter(Context context, ArrayList<Book> array) {
            super(context, 0, array);
            booksOriginal = array;
            booksFiltered = new ArrayList<>();
            booksFiltered.addAll(booksOriginal);
        }

        @Override
        public int getCount() {
            return booksFiltered.size();
        }

        @Override
        public Book getItem(int position) {
            return booksFiltered.get(position);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            Book book = getItem(position);
            // TODO use convert view with the view holder
            View view = inflater.inflate(R.layout.activity_main_item, parent, false);
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());

            TextView authorTextView = view.findViewById(R.id.authorTextView);
            TextView titleTextView = view.findViewById(R.id.titleTextView);
            TextView publisherTextView = view.findViewById(R.id.publisherTextView);
            TextView yearTextView = view.findViewById(R.id.yearTextView);
            ImageView imageView = view.findViewById(R.id.coverImageView);

            String titleText = book.title;
            if (sharedPref.getBoolean(SettingsActivity.ALLOW_BREAK_TITLES, true)) {
                titleText = titleText.replace(". ", ".\n");
            }

            authorTextView.setText(book.author);
            titleTextView.setText(titleText);
            publisherTextView.setText(book.publisher);
            yearTextView.setText(book.year);

            if (!book.cover.isEmpty()) {
                BitmapDownloaderTask task = new BitmapDownloaderTask(imageView, book.cover);
                task.execute();
            }

            return view;
        }

        @NonNull
        @Override
        public Filter getFilter() {
            if (filter == null) {
                filter = new BookFilter();
            }
            return filter;
        }

        private class BookFilter extends Filter {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String text = constraint.toString().toLowerCase();
                FilterResults result = new FilterResults();

                ArrayList<Book> filtered = new ArrayList<>();

                for (Book book : booksOriginal) {
                    if (book.version < 0) {
                        continue;
                    }

                    if (text.isEmpty()) {
                        filtered.add(book);
                    } else {
                        if (book.author.toLowerCase().contains(text)) {
                            filtered.add(book);
                        }
                        if (book.title.toLowerCase().contains(text)) {
                            filtered.add(book);
                        }
                    }
                }

                result.values = filtered;
                result.count = filtered.size();

                return result;
            }

            @SuppressWarnings("unchecked")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                booksFiltered = (ArrayList<Book>)results.values;
                notifyDataSetChanged();
                clear();

                for (int i = 0, l = booksFiltered.size(); i < l; i++) {
                    add(booksFiltered.get(i));
                    notifyDataSetInvalidated();
                }
            }
        }
    }

    private class OnItemClickListener implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Book book = (Book)parent.getItemAtPosition(position);

            Intent intent = new Intent(view.getContext(), ShowBookActivity.class);
            intent.putExtra("Book", book);
            startActivityForResult(intent, SHOW_BOOK_REQUEST);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, view, menuInfo);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.context_menu, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)item.getMenuInfo();

        Book book;

        switch (item.getItemId()) {
            case R.id.context_menu_edit:
                book = adapterData.get(info.position);

                Intent intent = new Intent(context, EditBookActivity.class);
                intent.putExtra("Book", book);
                startActivityForResult(intent, EDIT_BOOK_REQUEST);

                return true;
            case R.id.context_menu_remove:
                book = adapterData.get(info.position);

                bookcase.remove(book);
                saveData();
                synchronizeData();
                reloadData();

                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    protected synchronized void reloadData() {
        Collections.sort(adapterData, comparator);
        adapter.getFilter().filter("");
    }

    protected synchronized void saveData() {
        try {
            bookcase.saveAsXml(dataFile);
        } catch (IOException e) {
            Log.e(tag, "Error on saving data: " + e.getMessage());
        }
    }

    protected synchronized void synchronizeData() {
        boolean sync_enabled = sharedPref.getBoolean(SettingsActivity.SYNC_ENABLED, false);
        final String sync_location = sharedPref.getString(SettingsActivity.SYNC_LOCATION, "");

        Thread toastStart = new Thread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, R.string.sync_started, Toast.LENGTH_SHORT).show();
            }
        });

        Thread toastEnd = new Thread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, R.string.sync_finished, Toast.LENGTH_SHORT).show();
            }
        });

        Thread toastError = new Thread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, R.string.sync_error, Toast.LENGTH_LONG).show();
            }
        });

        if (sync_location.isEmpty()) {
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putBoolean(SettingsActivity.SYNC_ENABLED, false);
            editor.commit();
            sync_enabled = false;
        }

        if (sync_enabled) {
            try {
                runOnUiThread(toastStart);

                String name = "smb://" + sync_location + "/books.xml";
                SmbFile syncFile = new SmbFile(name);
                bookcase.synchronizeWithSmb(syncFile);

                runOnUiThread(toastEnd);
            } catch (Exception e) {
                Log.e(tag, "Synchronization not finished: " + e.getMessage());
                runOnUiThread(toastError);
            }
        }
    }

    protected void startUpdate() {
        Thread sync = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronizeData();
                saveData();

                runOnUiThread(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        reloadData();
                    }
                }));
            }
        });

        sync.start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        context = getApplicationContext();
        sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        dataFile = new File(getFilesDir(), "books.xml");
        bookcase = new Bookcase();

        setCompareMethod(null);

        adapterData = bookcase.getBooks();
        Collections.sort(adapterData, Book.TitleComparator);
        adapter = new BookArrayAdapter(this, adapterData);
        ListView listView = findViewById(R.id.listView);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new OnItemClickListener());
        registerForContextMenu(listView);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeAsUpIndicator(R.drawable.ic_menu);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addButtonClick(view);
            }
        });

        setNavigationViewListener();

        try {
            bookcase.loadFromXml(dataFile);
        } catch (FileNotFoundException e) {
            Log.i(tag, "Data file not found: " + e.getMessage());
        } catch (XmlPullParserException e) {
            Log.e(tag, "Data file format error: " + e.getMessage());
        } catch (IOException e) {
            Log.e(tag, "Data file unknown error: " + e.getMessage());
        }

        reloadData();

        startUpdate();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);

        switch (item.getItemId()) {
            case android.R.id.home:
                drawerLayout.openDrawer(GravityCompat.START);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    protected void setNavigationViewListener() {
        final DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        final NavigationView navigationView = findViewById(R.id.nav_view);

        navigationView.setNavigationItemSelectedListener(
                new NavigationView.OnNavigationItemSelectedListener() {
                    @Override
                    public boolean onNavigationItemSelected(@NonNull MenuItem item) {

                        item.setChecked(true);

                        switch (item.getItemId()) {
                            case R.id.title_sort_drawer:
                                setCompareMethod(SettingsActivity.SORT_METHOD_TITLE);
                                reloadData();
                                break;
                            case R.id.author_sort_drawer:
                                setCompareMethod(SettingsActivity.SORT_METHOD_AUTHOR);
                                reloadData();
                                break;
                            case R.id.year_sort_drawer:
                                setCompareMethod(SettingsActivity.SORT_METHOD_YEAR);
                                reloadData();
                                break;
                            case R.id.settings_drawer:
                                openSettingsClick(null);
                                break;
                        }

                        item.setChecked(false);
                        drawerLayout.closeDrawers();

                        return true;
                    }
                }
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case ADD_BOOK_REQUEST:
                if (resultCode == Activity.RESULT_OK) {
                    Book book = data.getParcelableExtra("Book");
                    bookcase.add(book);
                    startUpdate();
                }
                break;
            case EDIT_BOOK_REQUEST:
                if (resultCode == Activity.RESULT_OK) {
                    Book book = data.getParcelableExtra("Book");
                    bookcase.set(book);
                    startUpdate();
                }
                break;
            case SHOW_BOOK_REQUEST:
                break;
            default:
                break;
        }
    }

    private void setCompareMethod(String method) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPref.edit();

        if (method == null) {
            method = sharedPref.getString(SettingsActivity.SORT_METHOD, "");
        }

        switch (method) {
            case SettingsActivity.SORT_METHOD_AUTHOR:
                editor.putString(SettingsActivity.SORT_METHOD, SettingsActivity.SORT_METHOD_AUTHOR);
                comparator = Book.AuthorComparator;
                break;
            case SettingsActivity.SORT_METHOD_TITLE:
                editor.putString(SettingsActivity.SORT_METHOD, SettingsActivity.SORT_METHOD_TITLE);
                comparator = Book.TitleComparator;
                break;
            case SettingsActivity.SORT_METHOD_YEAR:
                editor.putString(SettingsActivity.SORT_METHOD, SettingsActivity.SORT_METHOD_YEAR);
                comparator = Book.YearComparator;
                break;
            default:
                editor.putString(SettingsActivity.SORT_METHOD, SettingsActivity.SORT_METHOD_TITLE);
                comparator = Book.TitleComparator;
                break;
        }

        editor.commit();
    }

    public void addButtonClick(View view) {
        Book book = new Book();
        Intent intent = new Intent(view.getContext(), EditBookActivity.class);
        intent.putExtra("Book", book);
        startActivityForResult(intent, ADD_BOOK_REQUEST);
    }

    public void openSettingsClick(View view) {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }
}
