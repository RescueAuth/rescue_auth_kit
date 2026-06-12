import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/vault/vault_session.dart';
import '../../l10n/app_localizations.dart';
import '../accounts/add_credential_sheet.dart';
import '../accounts/providers_list_screen.dart';
import '../developer/developer_screen.dart';
import '../settings/settings_screen.dart';

enum _HomeDestination { providers, developer, settings }

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  _HomeDestination _selected = _HomeDestination.providers;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    context.watch<VaultSession>();
    final destinations =
        <
          ({
            _HomeDestination destination,
            IconData icon,
            String label,
            Widget page,
          })
        >[
          (
            destination: _HomeDestination.providers,
            icon: Icons.shield,
            label: l10n.tabProviders,
            page: const ProvidersListScreen(),
          ),
          (
            destination: _HomeDestination.developer,
            icon: Icons.code,
            label: l10n.tabDeveloper,
            page: const DeveloperScreen(),
          ),
          (
            destination: _HomeDestination.settings,
            icon: Icons.settings,
            label: l10n.tabSettings,
            page: const SettingsScreen(),
          ),
        ];
    final selectedDestination =
        destinations.any((item) => item.destination == _selected)
        ? _selected
        : _HomeDestination.settings;
    final selectedIndex = destinations.indexWhere(
      (item) => item.destination == selectedDestination,
    );

    return Scaffold(
      appBar: AppBar(
        title: Text(destinations[selectedIndex].label),
        actions: [
          IconButton(
            tooltip: l10n.lockTooltip,
            onPressed: () => context.read<VaultSession>().lock(),
            icon: const Icon(Icons.lock),
          ),
        ],
      ),
      body: IndexedStack(
        index: selectedIndex,
        children: destinations.map((item) => item.page).toList(),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: selectedIndex,
        onDestinationSelected: (index) =>
            setState(() => _selected = destinations[index].destination),
        destinations: destinations
            .map(
              (item) => NavigationDestination(
                icon: Icon(item.icon),
                label: item.label,
              ),
            )
            .toList(),
      ),
      floatingActionButton: _buildFab(context, selectedDestination),
    );
  }

  Widget? _buildFab(BuildContext context, _HomeDestination destination) {
    if (destination == _HomeDestination.providers) {
      return FloatingActionButton(
        onPressed: () => AddCredentialSheet.show(context),
        child: const Icon(Icons.add),
      );
    }
    if (destination == _HomeDestination.developer) {
      return FloatingActionButton(
        onPressed: () => showAddDeveloperEntrySheet(context),
        child: const Icon(Icons.add),
      );
    }
    return null;
  }
}
