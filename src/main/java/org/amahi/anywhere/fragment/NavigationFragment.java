/*
 * Copyright (c) 2014 Amahi
 *
 * This file is part of Amahi.
 *
 * Amahi is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Amahi is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Amahi. If not, see <http ://www.gnu.org/licenses/>.
 */

package org.amahi.anywhere.fragment;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OnAccountsUpdateListener;
import android.accounts.OperationCanceledException;
import android.app.Fragment;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Spinner;

import com.squareup.otto.Subscribe;

import org.amahi.anywhere.AmahiApplication;
import org.amahi.anywhere.R;
import org.amahi.anywhere.account.AmahiAccount;
import org.amahi.anywhere.adapter.NavigationAdapter;
import org.amahi.anywhere.adapter.ServersAdapter;
import org.amahi.anywhere.bus.AppsSelectedEvent;
import org.amahi.anywhere.bus.BusProvider;
import org.amahi.anywhere.bus.ServerConnectedEvent;
import org.amahi.anywhere.bus.ServerConnectionChangedEvent;
import org.amahi.anywhere.bus.ServersLoadFailedEvent;
import org.amahi.anywhere.bus.ServersLoadedEvent;
import org.amahi.anywhere.bus.SettingsSelectedEvent;
import org.amahi.anywhere.bus.SharesSelectedEvent;
import org.amahi.anywhere.server.client.AmahiClient;
import org.amahi.anywhere.server.client.ServerClient;
import org.amahi.anywhere.server.model.Server;
import org.amahi.anywhere.util.ViewDirector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

public class NavigationFragment extends Fragment implements AccountManagerCallback<Bundle>,
	OnAccountsUpdateListener,
	AdapterView.OnItemSelectedListener,
	AdapterView.OnItemClickListener
{
	private static final class State
	{
		private State() {
		}

		public static final String SERVERS = "servers";
	}

	@Inject
	AmahiClient amahiClient;

	@Inject
	ServerClient serverClient;

	@Override
	public View onCreateView(LayoutInflater layoutInflater, ViewGroup container, Bundle savedInstanceState) {
		return layoutInflater.inflate(R.layout.fragment_navigation, container, false);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		setUpInjections();

		setUpSettingsMenu();

		setUpAuthenticationListener();

		setUpServers(savedInstanceState);
	}

	private void setUpInjections() {
		AmahiApplication.from(getActivity()).inject(this);
	}

	private void setUpSettingsMenu() {
		setHasOptionsMenu(true);
	}

	private void setUpAuthenticationListener() {
		getAccountManager().addOnAccountsUpdatedListener(this, null, false);
	}

	private AccountManager getAccountManager() {
		return AccountManager.get(getActivity());
	}

	@Override
	public void onAccountsUpdated(Account[] accounts) {
		if (getAccounts().isEmpty()) {
			setUpAccount();
		}
	}

	private List<Account> getAccounts() {
		return Arrays.asList(getAccountManager().getAccountsByType(AmahiAccount.TYPE));
	}

	private void setUpAccount() {
		getAccountManager().addAccount(AmahiAccount.TYPE, AmahiAccount.TYPE_TOKEN, null, null, getActivity(), this, null);
	}

	private void setUpAuthenticationToken() {
		Account account = getAccounts().get(0);

		getAccountManager().getAuthToken(account, AmahiAccount.TYPE, null, getActivity(), this, null);
	}

	@Override
	public void run(AccountManagerFuture<Bundle> accountManagerFuture) {
		try {
			Bundle accountManagerResult = accountManagerFuture.getResult();

			String authenticationToken = accountManagerResult.getString(AccountManager.KEY_AUTHTOKEN);

			if (authenticationToken != null) {
				setUpServers(authenticationToken);
			} else {
				setUpAuthenticationToken();
			}
		} catch (OperationCanceledException e) {
			tearDownActivity();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (AuthenticatorException e) {
			throw new RuntimeException(e);
		}
	}

	private void tearDownActivity() {
		getActivity().finish();
	}

	private void setUpServers(Bundle state) {
		setUpServersAdapter();
		setUpServersContent(state);
		setUpServersListener();
	}

	private void setUpServersAdapter() {
		getServersSpinner().setAdapter(new ServersAdapter(getActivity()));
	}

	private Spinner getServersSpinner() {
		return (Spinner) getView().findViewById(R.id.spinner_servers);
	}

	private void setUpServersContent(Bundle state) {
		if (isServersStateValid(state)) {
			setUpServersState(state);
			setUpNavigation();
		} else {
			setUpAuthentication();
		}
	}

	private boolean isServersStateValid(Bundle state) {
		return (state != null) && state.containsKey(State.SERVERS);
	}

	private void setUpServersState(Bundle state) {
		List<Server> servers = state.getParcelableArrayList(State.SERVERS);

		setUpServersContent(servers);

		showContent();
	}

	private void setUpServersContent(List<Server> servers) {
		getServersAdapter().replaceWith(filterActiveServers(servers));
	}

	private ServersAdapter getServersAdapter() {
		return (ServersAdapter) getServersSpinner().getAdapter();
	}

	private List<Server> filterActiveServers(List<Server> servers) {
		List<Server> activeServers = new ArrayList<Server>();

		for (Server server : servers) {
			if (server.isActive()) {
				activeServers.add(server);
			}
		}

		return activeServers;
	}

	private void showContent() {
		ViewDirector.of(this, R.id.animator_content).show(R.id.layout_content);
	}

	private void setUpAuthentication() {
		if (getAccounts().isEmpty()) {
			setUpAccount();
		} else {
			setUpAuthenticationToken();
		}
	}

	private void setUpServers(String authenticationToken) {
		setUpServersAdapter();
		setUpServersContent(authenticationToken);
		setUpServersListener();
	}

	private void setUpServersContent(String authenticationToken) {
		amahiClient.getServers(authenticationToken);
	}

	@Subscribe
	public void onServersLoaded(ServersLoadedEvent event) {
		setUpServersContent(event.getServers());

		setUpNavigation();

		showContent();
	}

	private void setUpNavigation() {
		setUpNavigationAdapter();
		setUpNavigationListener();
	}

	private void setUpNavigationAdapter() {
		if (!serverClient.isConnected()) {
			getNavigationListView().setAdapter(NavigationAdapter.newRemoteAdapter(getActivity()));
			return;
		}

		if (serverClient.isConnectedLocal()) {
			getNavigationListView().setAdapter(NavigationAdapter.newLocalAdapter(getActivity()));
		} else {
			getNavigationListView().setAdapter(NavigationAdapter.newRemoteAdapter(getActivity()));
		}
	}

	private ListView getNavigationListView() {
		return (ListView) getView().findViewById(R.id.list_navigation);
	}

	private void setUpNavigationListener() {
		getNavigationListView().setOnItemClickListener(this);
	}

	@Override
	public void onItemClick(AdapterView<?> navigationListView, View navigationView, int navigationPosition, long navigationId) {
		switch (navigationPosition) {
			case NavigationAdapter.NavigationItems.SHARES:
				BusProvider.getBus().post(new SharesSelectedEvent());
				break;

			case NavigationAdapter.NavigationItems.APPS:
				BusProvider.getBus().post(new AppsSelectedEvent());
				break;

			default:
				break;
		}
	}

	@Subscribe
	public void onServersLoadFailed(ServersLoadFailedEvent event) {
		showError();
	}

	private void showError() {
		ViewDirector.of(this, R.id.animator_content).show(R.id.layout_error);
	}

	private void setUpServersListener() {
		getServersSpinner().setOnItemSelectedListener(this);
	}

	@Override
	public void onNothingSelected(AdapterView<?> spinnerView) {
	}

	@Override
	public void onItemSelected(AdapterView<?> spinnerView, View view, int position, long id) {
		Server server = getServersAdapter().getItem(position);

		setUpServerConnection(server);
	}

	private void setUpServerConnection(Server server) {
		if (serverClient.isConnected(server)) {
			setUpServerConnection();
			setUpServerNavigation();
		} else {
			serverClient.connect(server);
		}
	}

	@Subscribe
	public void onServerConnected(ServerConnectedEvent event) {
		setUpServerConnection();
		setUpServerNavigation();
	}

	private void setUpServerConnection() {
		if (!isConnectionAvailable() || isConnectionAuto()) {
			serverClient.connectAuto();
			return;
		}

		if (isConnectionLocal()) {
			serverClient.connectLocal();
		} else {
			serverClient.connectRemote();
		}
	}

	private boolean isConnectionAvailable() {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());

		return preferences.contains(getString(R.string.preference_key_server_connection));
	}

	private boolean isConnectionAuto() {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
		String preferenceConnection = preferences.getString(getString(R.string.preference_key_server_connection), null);

		return preferenceConnection.equals(getString(R.string.preference_key_server_connection_auto));
	}

	private boolean isConnectionLocal() {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
		String preferenceConnection = preferences.getString(getString(R.string.preference_key_server_connection), null);

		return preferenceConnection.equals(getString(R.string.preference_key_server_connection_local));
	}

	private void setUpServerNavigation() {
		setUpNavigationAdapter();
	}

	@Subscribe
	public void onServerConnectionChanged(ServerConnectionChangedEvent event) {
		setUpServerNavigation();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		super.onCreateOptionsMenu(menu, menuInflater);

		menuInflater.inflate(R.menu.action_bar_navigation, menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem menuItem) {
		switch (menuItem.getItemId()) {
			case R.id.menu_settings:
				BusProvider.getBus().post(new SettingsSelectedEvent());
				return true;

			default:
				return super.onOptionsItemSelected(menuItem);
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		BusProvider.getBus().register(this);
	}

	@Override
	public void onPause() {
		super.onPause();

		BusProvider.getBus().unregister(this);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		tearDownServersState(outState);
	}

	private void tearDownServersState(Bundle state) {
		if (areServersLoaded()) {
			state.putParcelableArrayList(State.SERVERS, new ArrayList<Parcelable>(getServersAdapter().getItems()));
		}
	}

	private boolean areServersLoaded() {
		return getServersAdapter() != null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		tearDownAuthenticationListener();
	}

	private void tearDownAuthenticationListener() {
		getAccountManager().removeOnAccountsUpdatedListener(this);
	}
}
