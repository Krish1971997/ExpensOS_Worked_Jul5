package com.expenseos;

import android.os.Bundle;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.expenseos.util.AppConfig;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

public class MainActivity extends AppCompatActivity {

    private NavController navController;
    private DrawerLayout drawerLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = findViewById(R.id.drawer_layout);
        NavigationView sideNav = findViewById(R.id.side_navigation_view);

        // Bottom navigation
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        NavHostFragment navHostFragment = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        navController = navHostFragment.getNavController();

        // Bottom nav destinations
        AppBarConfiguration appBarConfig = new AppBarConfiguration.Builder(
                R.id.nav_home, R.id.nav_reports,
                R.id.nav_backup, R.id.nav_configuration)
                .setOpenableLayout(drawerLayout)
                .build();
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfig);
        NavigationUI.setupWithNavController(bottomNav, navController);

        // Side drawer (Settings, Books)
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.nav_open, R.string.nav_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        sideNav.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_settings) {
                navController.navigate(R.id.nav_configuration);
            } else if (id == R.id.nav_books) {
                navController.navigate(R.id.nav_books);
            } else if (id == R.id.nav_console) {
                navController.navigate(R.id.nav_console);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Update toolbar subtitle with active book name
        updateBookLabel();
    }

    public void updateBookLabel() {
        String bookName = AppConfig.get(this).getActiveBookName();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle("● " + bookName);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}
