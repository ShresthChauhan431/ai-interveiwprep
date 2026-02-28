import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
    AppBar,
    Avatar,
    Box,
    Button,
    Container,
    Divider,
    Drawer,
    IconButton,
    List,
    ListItem,
    ListItemButton,
    ListItemIcon,
    ListItemText,
    Menu,
    MenuItem,
    Toolbar,
    Typography,
    useMediaQuery,
    useTheme,
} from '@mui/material';
import {
    Close,
    Dashboard,
    History,
    Home,
    Login,
    Logout,
    Menu as MenuIcon,
    Person,
    PlayArrow,
    Psychology,
    Settings,
} from '@mui/icons-material';
import { useAuth } from '../../context/AuthContext';

// ============================================================
// Nav items
// ============================================================

const navItems = [
    { label: 'Home', path: '/', icon: <Home fontSize="small" /> },
    { label: 'Dashboard', path: '/dashboard', icon: <Dashboard fontSize="small" /> },
    { label: 'Start Interview', path: '/interview/start', icon: <PlayArrow fontSize="small" /> },
    { label: 'History', path: '/history', icon: <History fontSize="small" /> },
    { label: 'Profile', path: '/profile', icon: <Person fontSize="small" /> },
];

// ============================================================
// Navigation Component
// ============================================================

const Navigation: React.FC = () => {
    const navigate = useNavigate();
    const location = useLocation();
    const theme = useTheme();
    const isMobile = useMediaQuery(theme.breakpoints.down('md'));
    const { user, isAuthenticated, logout } = useAuth();

    const [mobileOpen, setMobileOpen] = useState(false);
    const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
    const menuOpen = Boolean(anchorEl);

    const handleMenuOpen = (e: React.MouseEvent<HTMLElement>) => setAnchorEl(e.currentTarget);
    const handleMenuClose = () => setAnchorEl(null);
    const toggleDrawer = () => setMobileOpen(!mobileOpen);

    const handleNavigate = (path: string) => {
        navigate(path);
        setMobileOpen(false);
        handleMenuClose();
    };

    const handleLogout = () => {
        handleMenuClose();
        setMobileOpen(false);
        logout();
    };

    const isActive = (path: string) => location.pathname === path;

    // User initials for avatar
    const initials = user?.name
        ? user.name.split(' ').map((n) => n[0]).join('').toUpperCase().slice(0, 2)
        : '?';

    // ============================================================
    // Mobile Drawer
    // ============================================================

    const drawer = (
        <Box sx={{ width: 280, height: '100%', display: 'flex', flexDirection: 'column' }}>
            {/* Header */}
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', p: 2 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Psychology color="primary" />
                    <Typography variant="h6" fontWeight={700}>InterviewAI</Typography>
                </Box>
                <IconButton onClick={toggleDrawer}>
                    <Close />
                </IconButton>
            </Box>
            <Divider />

            {/* User info */}
            {isAuthenticated && user && (
                <Box sx={{ p: 2, display: 'flex', alignItems: 'center', gap: 1.5 }}>
                    <Avatar sx={{ bgcolor: 'primary.main', width: 36, height: 36, fontSize: 14 }}>
                        {initials}
                    </Avatar>
                    <Box>
                        <Typography variant="body2" fontWeight={600}>{user.name}</Typography>
                        <Typography variant="caption" color="text.secondary">{user.email}</Typography>
                    </Box>
                </Box>
            )}

            {/* Nav links */}
            <List sx={{ flex: 1, px: 1 }}>
                {isAuthenticated ? (
                    navItems.map((item) => (
                        <ListItem key={item.path} disablePadding sx={{ mb: 0.5 }}>
                            <ListItemButton
                                onClick={() => handleNavigate(item.path)}
                                selected={isActive(item.path)}
                                sx={{ borderRadius: 2 }}
                            >
                                <ListItemIcon sx={{ minWidth: 36 }}>{item.icon}</ListItemIcon>
                                <ListItemText primary={item.label} />
                            </ListItemButton>
                        </ListItem>
                    ))
                ) : (
                    <>
                        <ListItem disablePadding sx={{ mb: 0.5 }}>
                            <ListItemButton onClick={() => handleNavigate('/login')} sx={{ borderRadius: 2 }}>
                                <ListItemIcon sx={{ minWidth: 36 }}><Login fontSize="small" /></ListItemIcon>
                                <ListItemText primary="Sign In" />
                            </ListItemButton>
                        </ListItem>
                        <ListItem disablePadding>
                            <ListItemButton onClick={() => handleNavigate('/register')} sx={{ borderRadius: 2 }}>
                                <ListItemIcon sx={{ minWidth: 36 }}><Person fontSize="small" /></ListItemIcon>
                                <ListItemText primary="Sign Up" />
                            </ListItemButton>
                        </ListItem>
                    </>
                )}
            </List>

            {/* Logout */}
            {isAuthenticated && (
                <>
                    <Divider />
                    <List sx={{ px: 1, pb: 1 }}>
                        <ListItem disablePadding>
                            <ListItemButton onClick={handleLogout} sx={{ borderRadius: 2 }}>
                                <ListItemIcon sx={{ minWidth: 36 }}><Logout fontSize="small" color="error" /></ListItemIcon>
                                <ListItemText primary="Logout" primaryTypographyProps={{ color: 'error' }} />
                            </ListItemButton>
                        </ListItem>
                    </List>
                </>
            )}
        </Box>
    );

    // ============================================================
    // Render
    // ============================================================

    return (
        <>
            <AppBar
                position="sticky"
                elevation={0}
                sx={{
                    bgcolor: 'background.paper',
                    borderBottom: '1px solid',
                    borderColor: 'divider',
                    color: 'text.primary',
                }}
            >
                <Container maxWidth="lg">
                    <Toolbar disableGutters sx={{ minHeight: 64 }}>
                        {/* Logo */}
                        <Box
                            sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer', mr: 4 }}
                            onClick={() => navigate(isAuthenticated ? '/dashboard' : '/')}
                        >
                            <Psychology color="primary" />
                            <Typography variant="h6" fontWeight={700} noWrap>
                                InterviewAI
                            </Typography>
                        </Box>

                        {/* Desktop nav */}
                        {!isMobile && (
                            <Box sx={{ display: 'flex', gap: 0.5, flex: 1 }}>
                                {isAuthenticated ? (
                                    navItems.map((item) => (
                                        <Button
                                            key={item.path}
                                            onClick={() => handleNavigate(item.path)}
                                            startIcon={item.icon}
                                            sx={{
                                                color: isActive(item.path) ? 'primary.main' : 'text.secondary',
                                                fontWeight: isActive(item.path) ? 700 : 500,
                                                bgcolor: isActive(item.path) ? (theme) => `${theme.palette.primary.main}0A` : 'transparent',
                                                '&:hover': { bgcolor: 'action.hover' },
                                            }}
                                        >
                                            {item.label}
                                        </Button>
                                    ))
                                ) : (
                                    <Box sx={{ flex: 1 }} />
                                )}
                            </Box>
                        )}

                        {/* Spacer for mobile */}
                        {isMobile && <Box sx={{ flex: 1 }} />}

                        {/* Right side: user menu or sign in */}
                        {!isMobile && (
                            isAuthenticated && user ? (
                                <>
                                    <IconButton onClick={handleMenuOpen} size="small" sx={{ ml: 1 }}>
                                        <Avatar sx={{ bgcolor: 'primary.main', width: 34, height: 34, fontSize: 14 }}>
                                            {initials}
                                        </Avatar>
                                    </IconButton>
                                    <Menu
                                        anchorEl={anchorEl}
                                        open={menuOpen}
                                        onClose={handleMenuClose}
                                        transformOrigin={{ horizontal: 'right', vertical: 'top' }}
                                        anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
                                        PaperProps={{ sx: { mt: 1, minWidth: 180, borderRadius: 2 } }}
                                    >
                                        <Box sx={{ px: 2, py: 1 }}>
                                            <Typography variant="body2" fontWeight={600}>{user.name}</Typography>
                                            <Typography variant="caption" color="text.secondary">{user.email}</Typography>
                                        </Box>
                                        <Divider />
                                        <MenuItem onClick={() => handleNavigate('/profile')}>
                                            <ListItemIcon><Person fontSize="small" /></ListItemIcon>
                                            Profile
                                        </MenuItem>
                                        <MenuItem onClick={() => handleNavigate('/profile')}>
                                            <ListItemIcon><Settings fontSize="small" /></ListItemIcon>
                                            Settings
                                        </MenuItem>
                                        <Divider />
                                        <MenuItem onClick={handleLogout}>
                                            <ListItemIcon><Logout fontSize="small" color="error" /></ListItemIcon>
                                            <Typography color="error">Logout</Typography>
                                        </MenuItem>
                                    </Menu>
                                </>
                            ) : (
                                <Box sx={{ display: 'flex', gap: 1 }}>
                                    <Button onClick={() => navigate('/login')} sx={{ color: 'text.secondary' }}>
                                        Sign In
                                    </Button>
                                    <Button variant="contained" onClick={() => navigate('/register')}>
                                        Get Started
                                    </Button>
                                </Box>
                            )
                        )}

                        {/* Mobile menu button */}
                        {isMobile && (
                            <IconButton onClick={toggleDrawer} edge="end">
                                <MenuIcon />
                            </IconButton>
                        )}
                    </Toolbar>
                </Container>
            </AppBar>

            {/* Mobile Drawer */}
            <Drawer
                anchor="right"
                open={mobileOpen}
                onClose={toggleDrawer}
                PaperProps={{ sx: { borderRadius: '16px 0 0 16px' } }}
            >
                {drawer}
            </Drawer>
        </>
    );
};

export default Navigation;
